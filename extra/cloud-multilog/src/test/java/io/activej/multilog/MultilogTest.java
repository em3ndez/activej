package io.activej.multilog;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.csp.process.frame.FrameFormat;
import io.activej.csp.process.frame.FrameFormats;
import io.activej.csp.process.transformer.ChannelTransformers;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.datastream.consumer.StreamConsumers;
import io.activej.datastream.consumer.ToListStreamConsumer;
import io.activej.datastream.supplier.StreamSupplierWithResult;
import io.activej.datastream.supplier.StreamSuppliers;
import io.activej.fs.FileMetadata;
import io.activej.fs.FileSystem;
import io.activej.reactor.Reactor;
import io.activej.serializer.BinarySerializers;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.common.collection.CollectionUtils.first;
import static io.activej.multilog.LogNamingScheme.NAME_PARTITION_REMAINDER_SEQ;
import static io.activej.promise.TestUtils.await;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class MultilogTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Parameter()
	public String testName;

	@Parameter(1)
	public FrameFormat frameFormat;

	@Parameter(2)
	public int endOfStreamBlockSize;

	@Parameters(name = "{0}")
	public static Collection<Object[]> getParameters() {
		return List.of(
			new Object[]{"LZ4 format", FrameFormats.lz4(), 8},
			new Object[]{"Legacy LZ4 format", FrameFormats.lz4Legacy(), 21}
		);
	}

	@Test
	public void testConsumer() {
		Reactor reactor = Reactor.getCurrentReactor();
		FileSystem fs = FileSystem.create(reactor, newSingleThreadExecutor(), temporaryFolder.getRoot().toPath());
		await(fs.start());
		IMultilog<String> multilog = Multilog.create(reactor, fs, frameFormat, BinarySerializers.UTF8_SERIALIZER, NAME_PARTITION_REMAINDER_SEQ);
		String testPartition = "testPartition";

		List<String> values = List.of("test1", "test2", "test3");

		await(StreamSuppliers.ofIterable(values)
			.streamTo(StreamConsumers.ofPromise(multilog.write(testPartition))));

		assertEquals(values, readLog(multilog, testPartition));
	}

	@Test
	public void testIgnoringTruncatedLogs() {
		Reactor reactor = Reactor.getCurrentReactor();
		Path storage = temporaryFolder.getRoot().toPath();
		FileSystem fs = FileSystem.create(reactor, newSingleThreadExecutor(), storage);
		await(fs.start());
		IMultilog<String> multilog = Multilog.builder(reactor, fs,
				frameFormat,
				BinarySerializers.UTF8_SERIALIZER,
				NAME_PARTITION_REMAINDER_SEQ)
			.withBufferSize(1)
			.build();

		String partition = "partition";

		List<String> values = List.of("test1", "test2", "test3");

		await(StreamSuppliers.ofIterable(values).streamTo(multilog.write(partition)));

		// Truncated data
		await(fs.list("*" + partition + "*")
			.then(map -> {
				Entry<String, FileMetadata> entry = first(map.entrySet());
				return fs.download(entry.getKey())
					.then(supplier -> supplier
						.transformWith(ChannelTransformers.rangeBytes(0, entry.getValue().getSize() - endOfStreamBlockSize))
						.streamTo(fs.upload(entry.getKey())));
			}));

		assertEquals(values, readLog(multilog, partition));
	}

	@Test
	public void testIgnoringMalformedLogs() {
		Reactor reactor = Reactor.getCurrentReactor();
		Path storage = temporaryFolder.getRoot().toPath();
		FileSystem fs = FileSystem.create(reactor, newSingleThreadExecutor(), storage);
		await(fs.start());
		IMultilog<String> multilog = Multilog.builder(reactor, fs,
				frameFormat,
				BinarySerializers.UTF8_SERIALIZER,
				NAME_PARTITION_REMAINDER_SEQ)
			.withIgnoreMalformedLogs(true)
			.build();

		String partition1 = "partition1";
		String partition2 = "partition2";

		List<String> values = List.of("test1", "test2", "test3");

		await(StreamSuppliers.ofIterable(values).streamTo(multilog.write(partition1)));
		await(StreamSuppliers.ofIterable(values).streamTo(multilog.write(partition2)));

		// malformed data
		await(fs.list("*" + partition1 + "*")
			.then(map -> {
				String filename = first(map.keySet());
				ByteBuf value = wrapUtf8("MALFORMED");
				return ChannelSuppliers.ofValue(value).streamTo(fs.upload(filename));
			}));

		// Unexpected data
		await(fs.list("*" + partition2 + "*")
			.then(map -> {
				String filename = first(map.keySet());
				return fs.download(filename)
					.then(supplier -> {
						ByteBuf value = wrapUtf8("UNEXPECTED DATA");
						return ChannelSuppliers.concat(supplier, ChannelSuppliers.ofValue(value))
							.streamTo(fs.upload(filename));
					});
			}));

		assertTrue(readLog(multilog, partition1).isEmpty());
		assertEquals(values, readLog(multilog, partition2));
	}

	@Test
	public void testIgnoringReadsPastFileSize() {
		Reactor reactor = Reactor.getCurrentReactor();
		Path storage = temporaryFolder.getRoot().toPath();
		FileSystem fs = FileSystem.create(reactor, newSingleThreadExecutor(), storage);
		await(fs.start());
		IMultilog<String> multilog = Multilog.builder(reactor, fs,
				frameFormat,
				BinarySerializers.UTF8_SERIALIZER, NAME_PARTITION_REMAINDER_SEQ)
			.withIgnoreMalformedLogs(true)
			.build();

		String partition = "partition";

		List<String> values = List.of("test1", "test2", "test3");

		await(StreamSuppliers.ofIterable(values).streamTo(multilog.write(partition)));

		ToListStreamConsumer<String> listConsumer = ToListStreamConsumer.create();
		await(fs.list("*" + partition + "*")
			.then(map -> {
				PartitionAndFile partitionAndFile = NAME_PARTITION_REMAINDER_SEQ.parse(first(map.keySet()));
				assertNotNull(partitionAndFile);
				LogFile logFile = partitionAndFile.logFile();
				return StreamSupplierWithResult.ofPromise(
						multilog.read(partition, logFile, first(map.values()).getSize() * 2, null))
					.getSupplier()
					.streamTo(listConsumer);
			}));

		assertTrue(listConsumer.getList().isEmpty());
	}

	@Test
	public void logPositionIsCountedCorrectly() {
		Reactor reactor = Reactor.getCurrentReactor();

		FileSystem fs = FileSystem.builder(reactor, newSingleThreadExecutor(), temporaryFolder.getRoot().toPath())
			.withReaderBufferSize(MemSize.bytes(1))
			.build();

		await(fs.start());

		IMultilog<String> multilog = Multilog.builder(reactor,
				fs,
				frameFormat,
				BinarySerializers.UTF8_SERIALIZER,
				NAME_PARTITION_REMAINDER_SEQ)
			.withBufferSize(MemSize.bytes(1))
			.build();

		String testPartition = "partition";

		List<String> values = List.of("test1", "test2", "test3");

		await(StreamSuppliers.ofIterable(values)
			.streamTo(StreamConsumers.ofPromise(multilog.write(testPartition))));

		StreamSupplierWithResult<String, LogPosition> supplierWithResult = StreamSupplierWithResult.ofPromise(
			multilog.read(testPartition, new LogFile("", 0), 0, null));

		ToListStreamConsumer<String> consumerToList = ToListStreamConsumer.create();
		await(supplierWithResult.getSupplier().streamTo(consumerToList));

		assertEquals(values, consumerToList.getList());

		LogPosition pos = await(supplierWithResult.getResult());

		long position = pos.getPosition();

		// check that position does not change on second call
		supplierWithResult = StreamSupplierWithResult.ofPromise(
			multilog.read(testPartition, pos.getLogFile(), position, null));

		consumerToList = ToListStreamConsumer.create();
		await(supplierWithResult.getSupplier().streamTo(consumerToList));

		assertTrue(consumerToList.getList().isEmpty());

		assertEquals(position, await(supplierWithResult.getResult()).getPosition());
	}

	private static <T> List<T> readLog(IMultilog<T> multilog, String partition) {
		ToListStreamConsumer<T> listConsumer = ToListStreamConsumer.create();
		await(StreamSupplierWithResult.ofPromise(
				multilog.read(partition, new LogFile("", 0), 0, null))
			.getSupplier()
			.streamTo(listConsumer));

		return listConsumer.getList();
	}

}

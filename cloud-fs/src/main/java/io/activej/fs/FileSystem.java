/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.fs;

import io.activej.async.function.AsyncBiFunctionEx;
import io.activej.async.service.ReactiveService;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.collection.CollectionUtils;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.UncheckedException;
import io.activej.common.function.RunnableEx;
import io.activej.common.function.SupplierEx;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.common.tuple.Tuple2;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.file.ChannelFileReader;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.csp.process.transformer.ChannelConsumerTransformer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.fs.exception.FileSystemException;
import io.activej.fs.exception.*;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.promise.Promise;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.activej.async.util.LogUtils.Level.TRACE;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.isBijection;
import static io.activej.common.Utils.noMergeFunction;
import static io.activej.common.function.BiConsumerEx.uncheckedOf;
import static io.activej.csp.process.transformer.ChannelConsumerTransformer.identity;
import static io.activej.fs.LocalFileUtils.*;
import static io.activej.fs.util.RemoteFileSystemUtils.batchException;
import static io.activej.fs.util.RemoteFileSystemUtils.ofFixedSize;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.nio.file.StandardOpenOption.*;

/**
 * An implementation of {@link IFileSystem} which operates on a real underlying filesystem, no networking involved.
 * <p>
 * Only permits file operations to be made within a specified storage path.
 * <p>
 * This implementation does not define new limitations, other than those defined in {@link IFileSystem} interface.
 */
public final class FileSystem extends AbstractReactive
	implements IFileSystem, ReactiveService, ReactiveJmxBeanWithStats {

	private static final Logger logger = LoggerFactory.getLogger(FileSystem.class);

	public static final String DEFAULT_TEMP_DIR = ".upload";
	public static final boolean DEFAULT_FSYNC_UPLOADS = ApplicationSettings.getBoolean(FileSystem.class, "fsyncUploads", false);
	public static final boolean DEFAULT_FSYNC_DIRECTORIES = ApplicationSettings.getBoolean(FileSystem.class, "fsyncDirectories", false);
	public static final boolean DEFAULT_FSYNC_APPENDS = ApplicationSettings.getBoolean(FileSystem.class, "fsyncAppends", false);

	private static final Set<StandardOpenOption> DEFAULT_APPEND_OPTIONS = Set.of(WRITE);
	private static final Set<StandardOpenOption> DEFAULT_APPEND_NEW_OPTIONS = Set.of(WRITE, CREATE);

	private final Path storage;
	private final Executor executor;

	private final Set<OpenOption> appendOptions = new HashSet<>(DEFAULT_APPEND_OPTIONS);
	private final Set<OpenOption> appendNewOptions = new HashSet<>(DEFAULT_APPEND_NEW_OPTIONS);

	private MemSize readerBufferSize = MemSize.kilobytes(256);
	private boolean hardLinkOnCopy = false;
	private Path tempDir;
	private boolean fsyncUploads = DEFAULT_FSYNC_UPLOADS;
	private boolean fsyncDirectories = DEFAULT_FSYNC_DIRECTORIES;

	private boolean started;

	CurrentTimeProvider now;

	//region JMX
	private final PromiseStats uploadBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats uploadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats listPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats movePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats moveAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deletePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deleteAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	//endregion

	private FileSystem(Reactor reactor, Path storage, Executor executor) {
		super(reactor);
		this.executor = executor;
		this.storage = storage;
		this.tempDir = storage.resolve(DEFAULT_TEMP_DIR);

		now = reactor;

		if (DEFAULT_FSYNC_APPENDS) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		}
	}

	public static FileSystem create(Reactor reactor, Executor executor, Path storageDir) {
		return builder(reactor, executor, storageDir).build();
	}

	public static Builder builder(Reactor reactor, Executor executor, Path storageDir) {
		return new FileSystem(reactor, storageDir.normalize(), executor).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, FileSystem> {
		private Builder() {}

		/**
		 * Sets the buffer size for reading files from the filesystem.
		 */
		public Builder withReaderBufferSize(MemSize size) {
			checkNotBuilt(this);
			readerBufferSize = size;
			return this;
		}

		/**
		 * If set to {@code true}, an attempt to create a hard link will be made when copying files
		 */
		@SuppressWarnings("UnusedReturnValue")
		public Builder withHardLinkOnCopy(boolean hardLinkOnCopy) {
			checkNotBuilt(this);
			FileSystem.this.hardLinkOnCopy = hardLinkOnCopy;
			return this;
		}

		/**
		 * Sets a temporary directory for files to be stored while uploading.
		 */
		public Builder withTempDir(Path tempDir) {
			checkNotBuilt(this);
			FileSystem.this.tempDir = tempDir;
			return this;
		}

		/**
		 * If set to {@code true}, all uploaded files will be synchronously persisted to the storage device.
		 * <p>
		 * <b>Note: may be slow when there are a lot of new files uploaded</b>
		 */
		public Builder withFSyncUploads(boolean fsync) {
			checkNotBuilt(this);
			FileSystem.this.fsyncUploads = fsync;
			return this;
		}

		/**
		 * If set to {@code true}, all newly created directories as well all changes to the directories
		 * (e.g. adding new files, updating existing, etc.)
		 * will be synchronously persisted to the storage device.
		 * <p>
		 * <b>Note: may be slow when there are a lot of new directories created or changed</b>
		 */
		public Builder withFSyncDirectories(boolean fsync) {
			checkNotBuilt(this);
			FileSystem.this.fsyncDirectories = fsync;
			return this;
		}

		/**
		 * If set to {@code true}, each write to {@link IFileSystem#append} consumer will be synchronously written to the storage device.
		 * <p>
		 * <b>Note: significantly slows down appends</b>
		 */
		public Builder withFSyncAppends(boolean fsync) {
			checkNotBuilt(this);
			if (fsync) {
				appendOptions.add(SYNC);
				appendNewOptions.add(SYNC);
			} else {
				appendOptions.remove(SYNC);
				appendNewOptions.remove(SYNC);
			}
			return this;
		}

		@Override
		protected FileSystem doBuild() {
			return FileSystem.this;
		}
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(String name) {
		checkInReactorThread(this);
		checkStarted();
		return uploadImpl(name, identity())
			.whenComplete(toLogger(logger, TRACE, "upload", name, this));
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(String name, long size) {
		checkInReactorThread(this);
		checkStarted();
		return uploadImpl(name, ofFixedSize(size))
			.whenComplete(toLogger(logger, TRACE, "upload", name, size, this));
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(String name, long offset) {
		checkInReactorThread(this);
		checkStarted();
		checkArgument(offset >= 0, "Offset cannot be less than 0");
		return execute(
			() -> {
				Path path = resolve(name);
				FileChannel channel;
				if (offset == 0) {
					channel = ensureTarget(null, path, () -> FileChannel.open(path, appendNewOptions));
					if (fsyncDirectories) {
						tryFsync(path.getParent());
					}
				} else {
					channel = FileChannel.open(path, appendOptions);
				}
				long size = channel.size();
				if (size < offset) {
					throw new IllegalOffsetException("Offset " + offset + " exceeds file size " + size);
				}
				return channel;
			})
			.then(translateScalarErrorsFn(name))
			.whenComplete(appendBeginPromise.recordStats())
			.map(channel -> ChannelFileWriter.builder(reactor, executor, channel)
				.withOffset(offset)
				.setIf(ChannelFileWriter.Builder::withForceOnClose, true, $ ->
					fsyncUploads && !appendOptions.contains(SYNC))
				.build()
				.withAcknowledgement(ack -> ack
					.then(translateScalarErrorsFn())
					.whenComplete(appendFinishPromise.recordStats())
					.whenComplete(toLogger(logger, TRACE, "onAppendComplete", name, offset, this))))
			.whenComplete(toLogger(logger, TRACE, "append", name, offset, this));
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(String name, long offset, long limit) {
		checkInReactorThread(this);
		checkStarted();
		checkArgument(offset >= 0, "offset < 0");
		checkArgument(limit >= 0, "limit < 0");
		return execute(
			() -> {
				Path path = resolve(name);
				FileChannel channel = FileChannel.open(path, READ);
				long size = channel.size();
				if (size < offset) {
					throw new IllegalOffsetException("Offset " + offset + " exceeds file size " + size);
				}
				return channel;
			})
			.map(channel -> ChannelFileReader.builder(reactor, executor, channel)
				.withBufferSize(readerBufferSize)
				.withOffset(offset)
				.withLimit(limit)
				.build()
				.withEndOfStream(eos -> eos
					.then(translateScalarErrorsFn(name))
					.whenComplete(downloadFinishPromise.recordStats())
					.whenComplete(toLogger(logger, TRACE, "onDownloadComplete", name, offset, limit))))
			.then(translateScalarErrorsFn(name))
			.whenComplete(toLogger(logger, TRACE, "download", name, offset, limit, this))
			.whenComplete(downloadBeginPromise.recordStats());
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(String glob) {
		checkInReactorThread(this);
		checkStarted();
		if (glob.isEmpty()) return Promise.of(Map.of());

		return execute(
			() -> {
				String subdir = extractSubDir(glob);
				Path subdirectory = resolve(subdir);
				String subglob = glob.substring(subdir.length());

				return LocalFileUtils.findMatching(tempDir, subglob, subdirectory).stream()
					.collect(Collector.of(
						(Supplier<Map<String, FileMetadata>>) HashMap::new,
						uncheckedOf((map, path) -> {
							FileMetadata metadata = toFileMetadata(path);
							if (metadata != null) {
								String filename = TO_REMOTE_NAME.apply(storage.relativize(path).toString());
								map.put(filename, metadata);
							}
						}),
						noMergeFunction())
					);
			})
			.then(translateScalarErrorsFn())
			.whenComplete(toLogger(logger, TRACE, "list", glob, this))
			.whenComplete(listPromise.recordStats());
	}

	@Override
	public Promise<Void> copy(String name, String target) {
		checkInReactorThread(this);
		checkStarted();
		return execute(() -> forEachPair(Map.of(name, target), this::doCopy))
			.then(translateScalarErrorsFn())
			.whenComplete(toLogger(logger, TRACE, "copy", name, target, this))
			.whenComplete(copyPromise.recordStats());
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		checkInReactorThread(this);
		checkStarted();
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return execute(() -> forEachPair(sourceToTarget, this::doCopy))
			.whenComplete(toLogger(logger, TRACE, "copyAll", sourceToTarget, this))
			.whenComplete(copyAllPromise.recordStats());
	}

	@Override
	public Promise<Void> move(String name, String target) {
		checkInReactorThread(this);
		checkStarted();
		return execute(() -> forEachPair(Map.of(name, target), this::doMove))
			.then(translateScalarErrorsFn())
			.whenComplete(toLogger(logger, TRACE, "move", name, target, this))
			.whenComplete(movePromise.recordStats());
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		checkInReactorThread(this);
		checkStarted();
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return execute(() -> forEachPair(sourceToTarget, this::doMove))
			.whenComplete(toLogger(logger, TRACE, "moveAll", sourceToTarget, this))
			.whenComplete(moveAllPromise.recordStats());
	}

	@Override
	public Promise<Void> delete(String name) {
		checkInReactorThread(this);
		checkStarted();
		return execute(() -> deleteImpl(Set.of(name)))
			.then(translateScalarErrorsFn(name))
			.whenComplete(toLogger(logger, TRACE, "delete", name, this))
			.whenComplete(deletePromise.recordStats());
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		checkInReactorThread(this);
		checkStarted();
		if (toDelete.isEmpty()) return Promise.complete();

		return execute(() -> deleteImpl(toDelete))
			.whenComplete(toLogger(logger, TRACE, "deleteAll", toDelete, this))
			.whenComplete(deleteAllPromise.recordStats());
	}

	@Override
	public Promise<Void> ping() {
		checkInReactorThread(this);
		checkStarted();
		return Promise.complete(); // local fs is always available
	}

	@Override
	public Promise<@Nullable FileMetadata> info(String name) {
		checkInReactorThread(this);
		checkStarted();
		return execute(() -> toFileMetadata(resolve(name)))
			.whenComplete(toLogger(logger, TRACE, "info", name, this))
			.whenComplete(infoPromise.recordStats());
	}

	@Override
	public Promise<Map<String, FileMetadata>> infoAll(Set<String> names) {
		checkInReactorThread(this);
		checkStarted();
		if (names.isEmpty()) return Promise.of(Map.of());

		return execute(
			() -> {
				Map<String, FileMetadata> result = new HashMap<>();
				for (String name : names) {
					FileMetadata metadata = toFileMetadata(resolve(name));
					if (metadata != null) {
						result.put(name, metadata);
					}
				}
				return result;
			})
			.whenComplete(toLogger(logger, TRACE, "infoAll", names, this))
			.whenComplete(infoAllPromise.recordStats());
	}

	@Override
	public Promise<Void> start() {
		checkInReactorThread(this);
		return execute(() -> LocalFileUtils.init(storage, tempDir, fsyncDirectories))
			.whenResult(() -> started = true);
	}

	@Override
	public Promise<Void> stop() {
		checkInReactorThread(this);
		return Promise.complete();
	}

	@Override
	public String toString() {
		return "FileSystem{storage=" + storage + '}';
	}

	private static IsADirectoryException isADirectoryException(String name) {
		return new IsADirectoryException("Path '" + name + "' is a directory");
	}

	private Path resolve(String name) throws ForbiddenPathException {
		return LocalFileUtils.resolve(storage, tempDir, TO_LOCAL_NAME.apply(name));
	}

	private Promise<ChannelConsumer<ByteBuf>> uploadImpl(String name, ChannelConsumerTransformer<ByteBuf, ChannelConsumer<ByteBuf>> transformer) {
		return execute(
			() -> {
				Path tempPath = LocalFileUtils.createTempUploadFile(tempDir);
				return new Tuple2<>(tempPath, FileChannel.open(tempPath, CREATE, WRITE));
			})
			.map(pathAndChannel -> ChannelFileWriter.builder(reactor, executor, pathAndChannel.value2())
				.setIf(ChannelFileWriter.Builder::withForceOnClose, true, $ -> fsyncUploads)
				.build()
				.transformWith(transformer)
				.withAcknowledgement(ack -> ack
					.then(() -> execute(() -> {
						Path target = resolve(name);
						doMove(pathAndChannel.value1(), target);
						if (fsyncDirectories) {
							tryFsync(target.getParent());
						}
					}))
					.then(translateScalarErrorsFn())
					.whenException(() -> execute(() -> Files.deleteIfExists(pathAndChannel.value1())))
					.whenComplete(uploadFinishPromise.recordStats())
					.whenComplete(toLogger(logger, TRACE, "onUploadComplete", name, this))))
			.then(translateScalarErrorsFn(name))
			.whenComplete(uploadBeginPromise.recordStats());
	}

	private void forEachPair(Map<String, String> sourceToTargetMap, IOScalarBiConsumer consumer) throws FileSystemBatchException, FileSystemIOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				translateBatchErrors(entry, () -> {
					Path path = resolve(entry.getKey());
					if (Files.readAttributes(path, BasicFileAttributes.class).isDirectory()) {
						throw new IsADirectoryException("Path '" + entry.getKey() + "' is a directory");
					}
					Path targetPath = resolve(entry.getValue());
					if (path.equals(targetPath)) {
						touch(path, now);
						if (fsyncDirectories) {
							toFSync.add(path);
						}
						return;
					}

					consumer.accept(path, targetPath);
					if (fsyncDirectories) {
						toFSync.add(targetPath.getParent());
					}
				});
			}
		} finally {
			for (Path path : toFSync) {
				tryFsync(path);
			}
		}
	}

	private void doMove(Path path, Path targetPath) throws IOException, FileSystemScalarException {
		ensureTarget(path, targetPath, () -> moveViaHardlink(path, targetPath, now));
	}

	private void doCopy(Path path, Path targetPath) throws IOException, FileSystemScalarException {
		if (hardLinkOnCopy) {
			try {
				ensureTarget(path, targetPath, () -> copyViaHardlink(path, targetPath, now));
			} catch (IOException | FileSystemScalarException e) {
				logger.warn("Could not copy via hard link, trying to copy via temporary directory", e);
				try {
					ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
				} catch (IOException e2) {
					e.addSuppressed(e2);
					throw e;
				}
			}
		} else {
			ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
		}
	}

	private void deleteImpl(Set<String> toDelete) throws FileSystemBatchException, FileSystemIOException {
		for (String name : toDelete) {
			translateBatchErrors(name, () -> {
				Path path = resolve(name);
				// cannot delete storage
				if (path.equals(storage)) return;

				try {
					Files.deleteIfExists(path);
				} catch (DirectoryNotEmptyException e) {
					throw isADirectoryException(name);
				}
			});
		}
	}

	private <V> V ensureTarget(@Nullable Path source, Path target, IOCallable<V> afterCreation) throws IOException, FileSystemScalarException {
		if (tempDir.startsWith(target)) {
			throw new IsADirectoryException("Path '" + storage.relativize(target) + "' is a directory");
		}

		try {
			return LocalFileUtils.ensureTarget(source, target, fsyncDirectories, afterCreation);
		} catch (DirectoryNotEmptyException e) {
			throw isADirectoryException(storage.relativize(target).toString());
		} catch (java.nio.file.FileSystemException e) {
			throw new PathContainsFileException();
		}
	}

	private void ensureTarget(@Nullable Path source, Path target, IORunnable afterCreation) throws IOException, FileSystemScalarException {
		ensureTarget(source, target, () -> {
			afterCreation.run();
			return null;
		});
	}

	private @Nullable FileMetadata toFileMetadata(Path path) throws FileSystemIOException {
		try {
			return LocalFileUtils.toFileMetadata(path);
		} catch (IOException e) {
			logger.warn("Failed to retrieve metadata for {}", path, e);
			throw new FileSystemIOException("Failed to retrieve metadata");
		}
	}

	private <T> Promise<T> execute(SupplierEx<T> callable) {
		return Promise.ofBlocking(executor, callable);
	}

	private Promise<Void> execute(RunnableEx runnable) {
		return Promise.ofBlocking(executor, runnable);
	}

	private <T> AsyncBiFunctionEx<T, @Nullable Exception, T> translateScalarErrorsFn() {
		return translateScalarErrorsFn(null);
	}

	private <T> AsyncBiFunctionEx<T, @Nullable Exception, T> translateScalarErrorsFn(@Nullable String name) {
		return (v, e) -> {
			if (e == null) {
				return Promise.of(v);
			}
			while (e instanceof UncheckedException uncheckedException) {
				e = uncheckedException.getCause();
			}
			if (e instanceof FileSystemBatchException) {
				Map<String, FileSystemScalarException> exceptions = ((FileSystemBatchException) e).getExceptions();
				assert exceptions.size() == 1;
				throw CollectionUtils.first(exceptions.values());
			} else if (e instanceof FileSystemException || e instanceof MalformedDataException) {
				throw e;
			} else if (e instanceof FileAlreadyExistsException) {
				return execute(() -> {
					if (name != null && Files.isDirectory(resolve(name))) throw isADirectoryException(name);
					throw new PathContainsFileException();
				});
			} else if (e instanceof NoSuchFileException) {
				throw new FileNotFoundException();
			} else if (e instanceof GlobException) {
				throw new MalformedGlobException(e.getMessage());
			} else if (e instanceof FileSystemStructureException) {
				throw new FileSystemIOException(e.getMessage());
			}
			Exception finalE = e;
			return execute(() -> {
				if (name != null) {
					Path path = resolve(name);
					if (!Files.exists(path))
						throw new FileNotFoundException("File '" + name + "' not found");
					if (Files.isDirectory(path)) throw isADirectoryException(name);
				}
				logger.warn("Operation failed", finalE);
				if (finalE instanceof IOException) {
					throw new FileSystemIOException("IO Error");
				}
				throw new FileSystemIOException("Unknown error");
			});
		};
	}

	private void translateBatchErrors(Map.Entry<String, String> entry, IOScalarRunnable runnable) throws FileSystemBatchException, FileSystemIOException {
		String first = entry.getKey();
		String second = entry.getValue();
		try {
			runnable.run();
		} catch (FileSystemScalarException e) {
			throw batchException(first, e);
		} catch (FileAlreadyExistsException e) {
			checkIfDirectories(first, second);
			throw batchException(first, new PathContainsFileException());
		} catch (NoSuchFileException e) {
			throw batchException(first, new FileNotFoundException());
		} catch (FileSystemStructureException e) {
			throw new FileSystemIOException(e.getMessage());
		} catch (IOException e) {
			checkIfExists(first);
			checkIfDirectories(first, second);
			logger.warn("Operation failed", e);
			throw new FileSystemIOException("IO Error");
		} catch (Exception e) {
			logger.warn("Operation failed", e);
			throw new FileSystemIOException("Unknown Error");
		}
	}

	private void translateBatchErrors(String first, IOScalarRunnable runnable) throws FileSystemBatchException, FileSystemIOException {
		translateBatchErrors(new AbstractMap.SimpleEntry<>(first, null), runnable);
	}

	private void checkIfDirectories(String first, @Nullable String second) throws FileSystemBatchException {
		try {
			if (Files.isDirectory(resolve(first))) {
				throw batchException(first, isADirectoryException(first));
			}
		} catch (ForbiddenPathException e) {
			throw batchException(first, e);
		}
		try {
			if (Files.isDirectory(resolve(second))) {
				throw batchException(first, isADirectoryException(second));
			}
		} catch (ForbiddenPathException e) {
			throw batchException(first, e);
		}
	}

	private void checkIfExists(String file) throws FileSystemBatchException {
		try {
			if (!Files.exists(resolve(file))) {
				throw batchException(file, new FileNotFoundException("File '" + file + "' not found"));
			}
		} catch (ForbiddenPathException e) {
			throw batchException(file, e);
		}
	}

	private void checkStarted() {
		checkState(started, "FileSystem has not been started, call FileSystem#start first");
	}

	@FunctionalInterface
	public interface IOScalarRunnable {
		void run() throws IOException, FileSystemScalarException;
	}

	@FunctionalInterface
	public interface IOScalarBiConsumer {
		void accept(Path first, Path second) throws IOException, FileSystemScalarException;
	}

	//region JMX
	@JmxAttribute
	public PromiseStats getUploadBeginPromise() {
		return uploadBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getUploadFinishPromise() {
		return uploadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendBeginPromise() {
		return appendBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendFinishPromise() {
		return appendFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadBeginPromise() {
		return downloadBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadFinishPromise() {
		return downloadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getListPromise() {
		return listPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoPromise() {
		return infoPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoAllPromise() {
		return infoAllPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyPromise() {
		return copyPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyAllPromise() {
		return copyAllPromise;
	}

	@JmxAttribute
	public PromiseStats getMovePromise() {
		return movePromise;
	}

	@JmxAttribute
	public PromiseStats getMoveAllPromise() {
		return moveAllPromise;
	}

	@JmxAttribute
	public PromiseStats getDeletePromise() {
		return deletePromise;
	}

	@JmxAttribute
	public PromiseStats getDeleteAllPromise() {
		return deleteAllPromise;
	}
	//endregion
}

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

package io.activej.rpc.protocol;

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.MemSize;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.process.frame.ChannelFrameDecoder;
import io.activej.csp.process.frame.ChannelFrameEncoder;
import io.activej.csp.process.frame.FrameFormat;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.datastream.consumer.AbstractStreamConsumer;
import io.activej.datastream.csp.ChannelDeserializer;
import io.activej.datastream.csp.ChannelSerializer;
import io.activej.datastream.supplier.AbstractStreamSupplier;
import io.activej.datastream.supplier.StreamDataAcceptor;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.serializer.BinarySerializer;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

public final class RpcStream {
	private final ChannelDeserializer<RpcMessage> deserializer;
	private final ChannelSerializer<RpcMessage> serializer;
	private Listener listener;

	private final AbstractStreamConsumer<RpcMessage> internalConsumer = new AbstractStreamConsumer<>() {};

	private final AbstractStreamSupplier<RpcMessage> internalSupplier = new AbstractStreamSupplier<>() {
		@Override
		protected void onResumed() {
			deserializer.updateDataAcceptor();
			listener.onSenderReady(getDataAcceptor());
		}

		@Override
		protected void onSuspended() {
			if (server) {
				deserializer.updateDataAcceptor();
			}
			listener.onSenderSuspended();
		}

	};

	public interface Listener extends StreamDataAcceptor<RpcMessage> {
		void onReceiverEndOfStream();

		void onReceiverError(Exception e);

		void onSenderError(Exception e);

		void onSerializationError(RpcMessage message, Exception e);

		void onSenderReady(StreamDataAcceptor<RpcMessage> acceptor);

		void onSenderSuspended();
	}

	private final boolean server;
	private final ITcpSocket socket;

	public RpcStream(
		ITcpSocket socket,
		BinarySerializer<RpcMessage> inputSerializer, BinarySerializer<RpcMessage> outputSerializer,
		MemSize initialBufferSize, Duration autoFlushInterval, @Nullable FrameFormat frameFormat, boolean server
	) {
		this.server = server;
		this.socket = socket;

		ChannelSerializer<RpcMessage> serializer = ChannelSerializer.builder(outputSerializer)
			.withInitialBufferSize(initialBufferSize)
			.withAutoFlushInterval(autoFlushInterval)
			.withSerializationErrorHandler((message, e) -> listener.onSerializationError(message, e))
			.build();
		ChannelDeserializer<RpcMessage> deserializer = ChannelDeserializer.create(inputSerializer);

		if (frameFormat != null) {
			ChannelFrameDecoder decompressor = ChannelFrameDecoder.create(frameFormat);
			ChannelFrameEncoder compressor = ChannelFrameEncoder.create(frameFormat);

			ChannelSuppliers.ofSocket(socket).bindTo(decompressor.getInput());
			decompressor.getOutput().bindTo(deserializer.getInput());

			serializer.getOutput().bindTo(compressor.getInput());
			compressor.getOutput().set(ChannelConsumers.ofSocket(socket));
		} else {
			ChannelSuppliers.ofSocket(socket).bindTo(deserializer.getInput());
			serializer.getOutput().set(ChannelConsumers.ofSocket(socket));
		}

		deserializer.streamTo(internalConsumer);

		this.deserializer = deserializer;
		this.serializer = serializer;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
		deserializer.getEndOfStream()
			.whenResult(listener::onReceiverEndOfStream)
			.whenException(listener::onReceiverError);
		serializer.getAcknowledgement()
			.whenException(listener::onSenderError);
		internalSupplier.streamTo(serializer);
		internalConsumer.resume(this.listener);
	}

	public void receiverSuspend() {
		internalConsumer.suspend();
	}

	public void receiverResume() {
		internalConsumer.resume(listener);
	}

	public void sendEndOfStream() {
		internalSupplier.sendEndOfStream();
	}

	public void close() {
		closeEx(new AsyncCloseException("RPC Channel Closed"));
	}

	public void closeEx(Exception e) {
		socket.closeEx(e);
		serializer.closeEx(e);
		deserializer.closeEx(e);
	}
}

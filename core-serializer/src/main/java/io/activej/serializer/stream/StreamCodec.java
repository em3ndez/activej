package io.activej.serializer.stream;

import java.io.IOException;

public interface StreamCodec<T> extends StreamEncoder<T>, StreamDecoder<T> {

	static <T> StreamCodec<T> of(StreamEncoder<? super T> encoder, StreamDecoder<? extends T> decoder) {
		return new StreamCodec<T>() {
			@Override
			public void encode(StreamOutput output, T item) throws IOException {
				encoder.encode(output, item);
			}

			@Override
			public T decode(StreamInput input) throws IOException {
				return decoder.decode(input);
			}
		};
	}
}

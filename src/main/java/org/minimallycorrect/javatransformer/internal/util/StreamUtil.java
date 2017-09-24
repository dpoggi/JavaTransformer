package org.minimallycorrect.javatransformer.internal.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

import lombok.experimental.UtilityClass;

@UtilityClass
public class StreamUtil {
	public static byte[] readFully(InputStream is) {
		byte[] output = {};
		int position = 0;
		while (true) {
			int bytesToRead;
			if (position >= output.length) {
				bytesToRead = output.length + 4096;
				if (output.length < position + bytesToRead) {
					output = Arrays.copyOf(output, position + bytesToRead);
				}
			} else {
				bytesToRead = output.length - position;
			}
			int bytesRead;
			try {
				bytesRead = is.read(output, position, bytesToRead);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			if (bytesRead < 0) {
				if (output.length != position) {
					output = Arrays.copyOf(output, position);
				}
				break;
			}
			position += bytesRead;
		}
		return output;
	}
}

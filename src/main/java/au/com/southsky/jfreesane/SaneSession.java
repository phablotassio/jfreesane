package au.com.southsky.jfreesane;

import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;

/**
 * Represents a conversation taking place with a SANE daemon.
 * 
 * @author James Ring (sjr@jdns.org)
 */
public class SaneSession implements Closeable {

	private enum FrameType implements SaneEnum {
		GRAY(0), RGB(1), RED(2), GREEN(3), BLUE(4);

		private final int wireValue;

		FrameType(int wireValue) {
			this.wireValue = wireValue;
		}

		@Override
		public int getWireValue() {
			return wireValue;
		}
	}

	private static final int DEFAULT_PORT = 6566;

	private final Socket socket;
	private final SaneOutputStream outputStream;
	private final SaneInputStream inputStream;

	private SaneSession(Socket socket) throws IOException {
		this.socket = socket;
		this.outputStream = new SaneOutputStream(socket.getOutputStream());
		this.inputStream = new SaneInputStream(socket.getInputStream());
	}

	/**
	 * Establishes a connection to the SANE daemon running on the given host on
	 * the default SANE port.
	 */
	public static SaneSession withRemoteSane(InetAddress saneAddress)
			throws IOException {
		Socket socket = new Socket(saneAddress, DEFAULT_PORT);

		return new SaneSession(socket);
	}

	public SaneDevice getDevice(String name) throws IOException {
		initSane();

		return new SaneDevice(this, name, "", "", "");
	}

	public List<SaneDevice> listDevices() throws IOException {
		initSane();

		outputStream.write(SaneWord.forInt(1));
		return inputStream.readDeviceList();
	}

	@Override
	public void close() throws IOException {
		try {
			outputStream.write(SaneWord.forInt(10));
			outputStream.close();
		} finally {
			// Seems like an oversight that Socket is not Closeable?
			Closeables.closeQuietly(new Closeable() {
				@Override
				public void close() throws IOException {
					socket.close();
				}
			});
		}
	}

	SaneDeviceHandle openDevice(SaneDevice device) throws IOException {
		outputStream.write(SaneWord.forInt(2));
		outputStream.write(device.getName());

		SaneWord status = inputStream.readWord();

		if (status.integerValue() != 0) {
			throw new IOException("unexpected status (" + status.integerValue()
					+ ") while opening device");
		}

		SaneWord handle = inputStream.readWord();
		String resource = inputStream.readString();

		return new SaneDeviceHandle(status, handle, resource);
	}

	BufferedImage acquireImage(SaneDeviceHandle handle) throws IOException {
		SaneImage.Builder builder = new SaneImage.Builder();

		while (true) {
			outputStream.write(SaneWord.forInt(7));
			outputStream.write(handle.getHandle());

			{
				int status = inputStream.readWord().integerValue();
				if (status != 0) {
					throw new IOException("Unexpected status (" + status
							+ ") on image acquisition");
				}
			}

			int port = inputStream.readWord().integerValue();
			SaneWord byteOrder = inputStream.readWord();
			String resource = inputStream.readString();

			// TODO(sjr): use the correct byte order, also need to maybe
			// authenticate to the resource

			// Ask the server for the parameters of this scan
			outputStream.write(SaneWord.forInt(6));
			outputStream.write(handle.getHandle());

			Socket imageSocket = new Socket(socket.getInetAddress(), port);
			int status = inputStream.readWord().integerValue();

			if (status != 0) {
				throw new IOException("Unexpected status (" + status
						+ ") in get_parameters");
			}

			SaneParameters parameters = inputStream.readSaneParameters();
			FrameInputStream frameStream = new FrameInputStream(parameters,
					imageSocket.getInputStream());
			builder.addFrame(frameStream.readFrame());
			// imageSocket.close();

			if (parameters.isLastFrame()) {
				break;
			}
		}

		SaneImage image = builder.build();

		return image.toBufferedImage();
	}

	void closeDevice(SaneDeviceHandle handle) throws IOException {
		outputStream.write(SaneWord.forInt(3));
		outputStream.write(handle.getHandle());

		// read the dummy value from the wire, if it doesn't throw an exception
		// we assume the close was successful
		inputStream.readWord();
	}

	private void initSane() throws IOException {
		// RPC code
		outputStream.write(SaneWord.forInt(0));

		// version number
		outputStream.write(SaneWord.forSaneVersion(1, 0, 3));

		// username
		outputStream.write(System.getProperty("user.name"));

		inputStream.readWord();
		inputStream.readWord();
	}

	public class SaneInputStream extends InputStream {
		private InputStream wrappedStream;

		public SaneInputStream(InputStream wrappedStream) {
			this.wrappedStream = wrappedStream;
		}

		@Override
		public int read() throws IOException {
			return wrappedStream.read();
		}

		public List<SaneDevice> readDeviceList() throws IOException {
			// Status first
			readWord().integerValue();

			// now we're reading an array, decode the length of the array (which
			// includes the null if the array is non-empty)
			int length = readWord().integerValue() - 1;

			if (length <= 0) {
				return ImmutableList.of();
			}

			ImmutableList.Builder<SaneDevice> result = ImmutableList.builder();

			for (int i = 0; i < length; i++) {
				SaneDevice device = readSaneDevicePointer();
				if (device == null) {
					throw new IllegalStateException(
							"null pointer encountered when not expected");
				}

				result.add(device);
			}

			// read past a trailing byte in the response that I haven't figured
			// out yet...
			inputStream.readWord();

			return result.build();
		}

		/**
		 * Reads a single {@link SaneDevice} definition pointed to by the
		 * pointer at the current location in the stream. Returns {@code null}
		 * if the pointer is a null pointer.
		 */
		private SaneDevice readSaneDevicePointer() throws IOException {
			if (!readPointer()) {
				// TODO(sjr): why is there always a null pointer here?
				// return null;
			}

			// now we assume that there's a sane device ready to parse
			return readSaneDevice();
		}

		/**
		 * Reads a single pointer and returns {@code true} if it was non-null.
		 */
		private boolean readPointer() throws IOException {
			return readWord().integerValue() != 0;
		}

		private SaneDevice readSaneDevice() throws IOException {
			String deviceName = readString();
			String deviceVendor = readString();
			String deviceModel = readString();
			String deviceType = readString();

			return new SaneDevice(SaneSession.this, deviceName, deviceVendor,
					deviceModel, deviceType);
		}

		public String readString() throws IOException {
			// read the length
			int length = readWord().integerValue();

			if (length == 0) {
				return "";
			}

			// now read all the bytes
			byte[] input = new byte[length];
			if (read(input) != input.length) {
				throw new IllegalStateException(
						"truncated input while reading string");
			}

			// skip the null terminator
			return new String(input, 0, input.length - 1);
		}

		public SaneParameters readSaneParameters() throws IOException {
			int frame = readWord().integerValue();
			boolean lastFrame = readWord().integerValue() == 1;
			int bytesPerLine = readWord().integerValue();
			int pixelsPerLine = readWord().integerValue();
			int lines = readWord().integerValue();
			int depth = readWord().integerValue();

			return new SaneParameters(frame, lastFrame, bytesPerLine,
					pixelsPerLine, lines, depth);
		}

		public SaneWord readWord() throws IOException {
			return SaneWord.fromStream(this);
		}
	}

	public static class SaneOutputStream extends OutputStream {
		private OutputStream wrappedStream;

		public SaneOutputStream(OutputStream wrappedStream) {
			this.wrappedStream = wrappedStream;
		}

		@Override
		public void close() throws IOException {
			wrappedStream.close();
		}

		@Override
		public void flush() throws IOException {
			wrappedStream.flush();
		}

		@Override
		public void write(int b) throws IOException {
			wrappedStream.write(b);
		}

		public void write(String string) throws IOException {
			if (string.length() > 0) {
				write(SaneWord.forInt(string.length() + 1));
				for (char c : string.toCharArray()) {
					if (c == 0) {
						throw new IllegalArgumentException(
								"null characters not allowed");
					}

					write(c);
				}
			}

			write(0);
		}

		public void write(SaneWord word) throws IOException {
			write(word.getValue());
		}

		public void write(SaneEnum someEnum) throws IOException {
			write(SaneWord.forInt(someEnum.getWireValue()));
		}
	}

	public static class SaneWord {
		public static final int SIZE_IN_BYTES = 4;

		private final byte[] value;

		private SaneWord(byte[] value) {
			this.value = value;
		}

		public static SaneWord fromStream(InputStream input) throws IOException {
			byte[] newValue = new byte[SIZE_IN_BYTES];
			if (input.read(newValue) != newValue.length) {
				throw new IOException(
						"input stream was truncated while reading a word");
			}

			return new SaneWord(newValue);
		}

		public static SaneWord forInt(int value) {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream(
					SIZE_IN_BYTES);
			DataOutputStream stream = new DataOutputStream(byteStream);
			try {
				stream.writeInt(value);
			} catch (IOException e) {
				throw new IllegalArgumentException(e);
			}
			return new SaneWord(byteStream.toByteArray());
		}

		public static SaneWord forSaneVersion(int major, int minor, int build) {
			int result = (major & 0xff) << 24;
			result |= (minor & 0xff) << 16;
			result |= (build & 0xffff) << 0;
			return forInt(result);
		}

		public byte[] getValue() {
			return Arrays.copyOf(value, value.length);
		}

		public int integerValue() {
			try {
				return new DataInputStream(new ByteArrayInputStream(value))
						.readInt();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		public static SaneWord fromBytes(byte[] byteValue) {
			Preconditions.checkArgument(byteValue.length == SIZE_IN_BYTES);
			return new SaneWord(Arrays.copyOf(byteValue, SIZE_IN_BYTES));
		}
	}

	static class SaneDeviceHandle {
		private final SaneWord status;
		private final SaneWord handle;
		private final String resource;

		private SaneDeviceHandle(SaneWord status, SaneWord handle,
				String resource) {
			this.status = status;
			this.handle = handle;
			this.resource = resource;
		}

		public SaneWord getStatus() {
			return status;
		}

		public SaneWord getHandle() {
			return handle;
		}

		public String getResource() {
			return resource;
		}

		public boolean isAuthorizationRequired() {
			return !Strings.isNullOrEmpty(resource);
		}
	}

	public class SaneParameters {
		private final FrameType frame;
		private final boolean lastFrame;
		private final int bytesPerLine;
		private final int pixelsPerLine;
		private final int lineCount;
		private final int depthPerPixel;

		public SaneParameters(int frame, boolean lastFrame, int bytesPerLine,
				int pixelsPerLine, int lines, int depth) {
			this.frame = SaneEnums.valueOf(FrameType.class, frame);
			this.lastFrame = lastFrame;
			this.bytesPerLine = bytesPerLine;
			this.pixelsPerLine = pixelsPerLine;
			this.lineCount = lines;
			this.depthPerPixel = depth;
		}

		public FrameType getFrame() {
			return frame;
		}

		public boolean isLastFrame() {
			return lastFrame;
		}

		public int getBytesPerLine() {
			return bytesPerLine;
		}

		public int getPixelsPerLine() {
			return pixelsPerLine;
		}

		public int getLineCount() {
			return lineCount;
		}

		public int getDepthPerPixel() {
			return depthPerPixel;
		}
	}

	private static class FrameInputStream extends InputStream {
		private final SaneParameters parameters;
		private final InputStream underlyingStream;

		public FrameInputStream(SaneParameters parameters,
				InputStream underlyingStream) {
			this.parameters = parameters;
			this.underlyingStream = underlyingStream;
		}

		@Override
		public int read() throws IOException {
			return underlyingStream.read();
		}

		public Frame readFrame() throws IOException {
			byte[] bigArray = new byte[parameters.getBytesPerLine()
					* parameters.getLineCount()];

			int offset = 0;
			int bytesRead = 0;
			while ((bytesRead = readRecord(bigArray, offset)) > 0) {
				offset += bytesRead;
			}

			return new Frame(parameters, bigArray);
		}

		private int readRecord(byte[] destination, int offset)
				throws IOException {
			DataInputStream inputStream = new DataInputStream(this);
			long length = inputStream.readInt();

			if (length == 0xffffffff) {
				System.out.println("Reached end of records");
				return -1;
			}

			if (length > Integer.MAX_VALUE) {
				throw new IllegalStateException("TODO: support massive records");
			}

			int result = read(destination, offset, (int) length);
			if (result != length) {
				throw new IllegalStateException("read too few bytes (" + result
						+ "), was expecting " + length);
			}

			System.out.println("Read a record of " + result + " bytes");
			return result;
		}
	}

	public SaneOutputStream getOutputStream() {
		return outputStream;
	}

	public SaneInputStream getInputStream() {
		return inputStream;
	}

	private static class Frame {
		private final SaneParameters parameters;
		private final byte[] data;

		public Frame(SaneParameters parameters, byte[] data) {
			this.parameters = parameters;
			this.data = data;
		}

		public FrameType getType() {
			return parameters.getFrame();
		}

		public byte[] getData() {
			return data;
		}

		public int getBytesPerLine() {
			return parameters.getBytesPerLine();
		}

		public int getWidth() {
			return parameters.getPixelsPerLine();
		}

		public int getHeight() {
			return parameters.getLineCount();
		}

		public int getPixelDepth() {
			return parameters.getDepthPerPixel();
		}
	}

	private static class SaneImage {
		private static final Set<FrameType> singletonFrameTypes = Sets
				.immutableEnumSet(FrameType.GRAY, FrameType.RGB);

		private static final Set<FrameType> redGreenBlueFrameTypes = Sets
				.immutableEnumSet(FrameType.RED, FrameType.GREEN,
						FrameType.BLUE);

		private final List<Frame> frames;
		private final int depthPerPixel;
		private final int width;
		private final int height;
		private final int bytesPerLine;

		private SaneImage(List<Frame> frames, int depthPerPixel, int width,
				int height, int bytesPerLine) {
			// this ensures that in the 3-frame situation, they are always
			// arranged in the following order: red, green, blue
			this.frames = Ordering
					.explicit(FrameType.RED, FrameType.GREEN, FrameType.BLUE,
							FrameType.RGB, FrameType.GRAY)
					.onResultOf(new Function<Frame, FrameType>() {
						@Override
						public FrameType apply(Frame input) {
							return input.getType();
						}
					}).immutableSortedCopy(frames);
			this.depthPerPixel = depthPerPixel;
			this.width = width;
			this.height = height;
			this.bytesPerLine = bytesPerLine;
		}

		private List<Frame> getFrames() {
			return frames;
		}

		private int getDepthPerPixel() {
			return depthPerPixel;
		}

		private int getWidth() {
			return width;
		}

		private int getHeight() {
			return height;
		}

		private int getBytesPerLine() {
			return bytesPerLine;
		}

		public BufferedImage toBufferedImage() {
			DataBuffer buffer = asDataBuffer();

			if (getFrames().size() == redGreenBlueFrameTypes.size()) {
				// 3 frames, one or two bytes per sample, 3 samples per pixel
				WritableRaster raster = Raster.createBandedRaster(buffer,
						getWidth(), getHeight(), getBytesPerLine(), new int[] {
								0, 1, 2 }, new int[] { 0, 0, 0 }, new Point(0,
								0));

				ColorModel model = new ComponentColorModel(
						ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
						false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

				return new BufferedImage(model, raster, false, null);
			}

			// Otherwise we're in a one-frame situation
			if (depthPerPixel == 1) {
				// each bit in the data buffer represents one pixel (black or
				// white)
				WritableRaster raster = Raster.createPackedRaster(buffer,
						width, height, 1, new Point(0, 0));

				BufferedImage image = new BufferedImage(width, height,
						BufferedImage.TYPE_BYTE_BINARY);
				
				image.setData(raster);
				return image;
			}

			if (getDepthPerPixel() == 8 || getDepthPerPixel() == 16) {
				ColorSpace colorSpace;
				int[] bandOffsets;

				if (getFrames().get(0).getType() == FrameType.GRAY) {
					colorSpace = ColorSpace.getInstance(ColorSpace.CS_GRAY);
					bandOffsets = new int[] { 0 };
				} else /* RGB */{
					colorSpace = ColorSpace
							.getInstance(ColorSpace.CS_LINEAR_RGB);
					bandOffsets = new int[] { 0, 1, 2 };
				}

				int bytesPerPixel = bandOffsets.length * (depthPerPixel / 8);
				WritableRaster raster = Raster.createInterleavedRaster(buffer,
						width, height, bytesPerLine, bytesPerPixel,
						bandOffsets, new Point(0, 0));

				ColorModel model = new ComponentColorModel(colorSpace, false,
						false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

				return new BufferedImage(model, raster, false, null);
			}

			throw new IllegalStateException("Unsupported SaneImage type");
		}

		private DataBuffer asDataBuffer() {
			byte[][] buffers = new byte[getFrames().size()][];

			for (int i = 0; i < getFrames().size(); i++) {
				buffers[i] = getFrames().get(i).getData();
			}

			return new DataBufferByte(buffers, getFrames().get(0).getData().length);
		}

		public static class Builder {
			private final List<Frame> frames = Lists.newArrayList();
			private final Set<FrameType> frameTypes = EnumSet
					.noneOf(FrameType.class);

			private final WriteOnce<Integer> depthPerPixel = new WriteOnce<Integer>();
			private final WriteOnce<Integer> width = new WriteOnce<Integer>();
			private final WriteOnce<Integer> height = new WriteOnce<Integer>();
			private final WriteOnce<Integer> bytesPerLine = new WriteOnce<Integer>();

			public void addFrame(Frame frame) {
				Preconditions.checkArgument(
						!frameTypes.contains(frame.getType()),
						"Image already contains a frame of this type");
				Preconditions.checkArgument(frameTypes.isEmpty()
						|| !singletonFrameTypes.contains(frame.getType()),
						"The frame type is singleton but this image "
								+ "contains another frame");
				Preconditions.checkArgument(
						frames.isEmpty()
								|| frames.get(0).getData().length == frame
										.getData().length,
						"new frame has an inconsistent size");
				setPixelDepth(frame.getPixelDepth());
				setBytesPerLine(frame.getBytesPerLine());
				setWidth(frame.getWidth());
				setHeight(frame.getHeight());
				frameTypes.add(frame.getType());
				frames.add(frame);
			}

			public void setPixelDepth(int depthPerPixel) {
				Preconditions.checkArgument(depthPerPixel > 0,
						"depth must be positive");
				this.depthPerPixel.set(depthPerPixel);
			}

			public void setWidth(int width) {
				this.width.set(width);
			}

			public void setHeight(int height) {
				this.height.set(height);
			}

			public void setBytesPerLine(int bytesPerLine) {
				this.bytesPerLine.set(bytesPerLine);
			}

			public SaneImage build() {
				Preconditions.checkState(!frames.isEmpty(), "no frames");
				Preconditions.checkState(depthPerPixel.get() != null,
						"setPixelDepth must be called");
				Preconditions.checkState(width.get() != null,
						"setWidth must be called");
				Preconditions.checkState(height.get() != null,
						"setHeight must be called");
				Preconditions.checkState(bytesPerLine.get() != null,
						"setBytesPerLine must be called");

				// does the image contains a single instance of a singleton
				// frame?
				if (frames.size() == 1
						&& singletonFrameTypes
								.contains(frames.get(0).getType())) {
					return new SaneImage(frames, depthPerPixel.get(),
							width.get(), height.get(), bytesPerLine.get());
				}

				// otherwise, does it contain a red, green and blue frame?
				if (frames.size() == redGreenBlueFrameTypes.size()
						&& redGreenBlueFrameTypes.containsAll(frameTypes)) {
					return new SaneImage(frames, depthPerPixel.get(),
							width.get(), height.get(), bytesPerLine.get());
				}

				throw new IllegalStateException(
						"Image is not fully constructed. Frame types present: "
								+ frameTypes);
			}
		}
	}

	private static class WriteOnce<T> {
		private T value = null;

		public void set(T value) {
			if (this.value == null) {
				this.value = value;
			} else if (!value.equals(this.value)) {
				throw new IllegalArgumentException("Cannot overwrite with a "
						+ "different value");
			}
		}

		public T get() {
			return value;
		}
	}
}
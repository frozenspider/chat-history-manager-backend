// We're deliberately using simpler package, for the sake of cleaner JNI function names
package webp

import org.scijava.nativelib.NativeLoader

/**
 * JNI interface for Google WebP library (which has been amended with those functions)
 */
class WebpNative {
  NativeLoader.loadLibrary("libwebp")

  @native def getDecoderVersion: Int
  @native def getInfo(data: Array[Byte], dataSize: Long, width: Array[Int], height: Array[Int]): Int
  @native def decodeBGR(data: Array[Byte], dataSize: Long, width: Array[Int], height: Array[Int]): Array[Byte]
  @native def decodeBGRA(data: Array[Byte], dataSize: Long, width: Array[Int], height: Array[Int]): Array[Byte]
}

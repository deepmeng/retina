// code by jph
package ch.ethz.idsc.retina.dev.velodyne;

import java.nio.ByteBuffer;

public interface LidarRayDataListener {
  /** function is invoked with parameters that refer to previous sequence of laser data
   * 
   * @param usec microseconds from the top of the hour to the first laser firing in the packet
   * @param type */
  void timestamp(int usec, byte type);

  /** implementations can read LASERS * 3 bytes from byteBuffer:
   * 
   * for (int laser = 0; laser < LASERS; ++laser) {
   * int distance = byteBuffer.getShort() & 0xffff;
   * int intensity = byteBuffer.get();
   * }
   * 
   * <p>parameters depends on sensor
   * 
   * rotational
   * Velodyne [0, ..., 35999] in 100th of degree
   * Mark8 [0, ..., 10399] where 10400 corresponds to 360 degree
   * 
   * @param rotational
   * 
   * @param byteBuffer */
  void scan(int rotational, ByteBuffer byteBuffer);
}

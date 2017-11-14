// code by rvmoos
package ch.ethz.idsc.retina.dev.rimo;

import java.nio.ByteBuffer;

public class SdoMessage {
  public final byte sdoCommand;
  public final short mainIndex;
  public final byte subIndex;
  public final int sdoData;

  public SdoMessage(ByteBuffer byteBuffer) {
    sdoCommand = byteBuffer.get();
    mainIndex = byteBuffer.getShort();
    subIndex = byteBuffer.get();
    sdoData = byteBuffer.getInt();
  }

  void encode(ByteBuffer byteBuffer) {
    byteBuffer.put(sdoCommand);
    byteBuffer.putShort(mainIndex);
    byteBuffer.put(subIndex);
    byteBuffer.putInt(sdoData);
  }

  @Override
  public String toString() {
    return String.format("0x%02x 0x%04x 0x%02x %d", sdoCommand & 0xff, mainIndex & 0xffff, subIndex & 0xff, sdoData);
  }
}

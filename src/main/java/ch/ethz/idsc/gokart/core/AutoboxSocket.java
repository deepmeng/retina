// code by jph
package ch.ethz.idsc.gokart.core;

import java.net.DatagramPacket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.ethz.idsc.owl.math.state.ProviderRank;
import ch.ethz.idsc.retina.dev.linmot.LinmotGetListener;
import ch.ethz.idsc.retina.dev.rimo.RimoGetListener;
import ch.ethz.idsc.retina.util.StartAndStoppable;
import ch.ethz.idsc.retina.util.data.DataEvent;
import ch.ethz.idsc.retina.util.io.ByteArrayConsumer;
import ch.ethz.idsc.retina.util.io.DatagramSocketManager;
import ch.ethz.idsc.retina.util.math.SI;
import ch.ethz.idsc.tensor.Scalar;
import ch.ethz.idsc.tensor.qty.Quantity;

/** template argument T is {@link RimoGetListener}, {@link LinmotGetListener}, ...
 * 
 * Example interface on datahaki's computer
 * 
 * enx9cebe8143edb Link encap:Ethernet HWaddr 9c:eb:e8:14:3e:db inet
 * addr:192.168.1.1 Bcast:192.168.1.255 Mask:255.255.255.0 inet6 addr:
 * fe80::9eeb:e8ff:fe14:3edb/64 Scope:Link UP BROADCAST RUNNING MULTICAST
 * MTU:1500 Metric:1 RX packets:466380 errors:0 dropped:0 overruns:0 frame:0 TX
 * packets:233412 errors:0 dropped:0 overruns:0 carrier:0 collisions:0
 * txqueuelen:1000 RX bytes:643249464 (643.2 MB) TX bytes:17275914 (17.2 MB) */
public abstract class AutoboxSocket<GE extends DataEvent, PE extends DataEvent> implements StartAndStoppable {
  private final DatagramSocketManager datagramSocketManager;
  private final List<GetListener<GE>> getListeners = new CopyOnWriteArrayList<>();
  /* package */ final ByteArrayConsumer byteArrayConsumer = new ByteArrayConsumer() {
    @Override // from ByteArrayConsumer
    public void accept(byte[] data, int length) {
      ByteBuffer byteBuffer = ByteBuffer.wrap(data, 0, length);
      byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
      GE getEvent = createGetEvent(byteBuffer);
      synchronized (getListeners) {
        for (GetListener<GE> listener : getListeners)
          try {
            listener.getEvent(getEvent); // notify get listener
          } catch (Exception exception) {
            exception.printStackTrace();
          }
      }
    }
  };
  // ---
  // TODO JAN due to the special remove logic, the providers data structure should be in a separate class
  private final Set<PutProvider<PE>> providers = //
      new ConcurrentSkipListSet<>(PutProviderComparator.INSTANCE);
  private final List<PutListener<PE>> putListeners = new CopyOnWriteArrayList<>();
  private Timer timer;

  protected AutoboxSocket(int length, int local_port) {
    datagramSocketManager = //
        DatagramSocketManager.local(new byte[length], local_port, AutoboxDevice.LOCAL_ADDRESS);
    datagramSocketManager.addListener(byteArrayConsumer);
  }

  private PutProvider<PE> putProviderActive = null;

  @Override
  public final void start() {
    datagramSocketManager.start();
    timer = new Timer();
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        synchronized (providers) {
          for (PutProvider<PE> putProvider : providers) {
            Optional<PE> optional = putProvider.putEvent();
            if (optional.isPresent())
              try {
                putProviderActive = putProvider;
                PE putEvent = optional.get();
                byte[] data = putEvent.asArray();
                datagramSocketManager.send(getDatagramPacket(data));
                for (PutListener<PE> putListener : putListeners)
                  putListener.putEvent(putEvent); // notify put listener
                return;
              } catch (Exception exception) {
                exception.printStackTrace();
              }
          }
        }
        System.err.println("no command provided in " + getClass().getSimpleName());
      }
    }, 70, getPutPeriod_ms());
  }

  /***************************************************/
  public final int getPutProviderSize() {
    return providers.size();
  }

  public final int getPutListenersSize() {
    return putListeners.size();
  }

  public final int getGetListenersSize() {
    return getListeners.size();
  }

  public final String getPutProviderDesc() {
    return Objects.nonNull(putProviderActive) //
        ? putProviderActive.getClass().getSimpleName()
        : "<null>";
  }

  public final Optional<ProviderRank> getPutProviderRank() {
    return Optional.ofNullable(Objects.nonNull(putProviderActive) //
        ? putProviderActive.getProviderRank()
        : null);
  }

  /***************************************************/
  /** @return period between two successive commands issued to the microautobox */
  protected abstract long getPutPeriod_ms();

  protected abstract DatagramPacket getDatagramPacket(byte[] data) throws UnknownHostException;

  protected abstract GE createGetEvent(ByteBuffer byteBuffer);

  @Override
  public final void stop() {
    if (Objects.nonNull(timer)) {
      timer.cancel();
      timer = null;
    }
    datagramSocketManager.stop();
  }

  /** @return period in unit "s" */
  public final Scalar getPutPeriod() {
    return Quantity.of(getPutPeriod_ms() * 1E-3, SI.SECOND);
  }

  /***************************************************/
  public final void addPutProvider(PutProvider<PE> putProvider) {
    synchronized (providers) {
      boolean added = providers.add(putProvider);
      if (!added) {
        System.err.println(putProvider.getClass().getSimpleName());
        new RuntimeException("put provider not added").printStackTrace();
      }
    }
  }

  public final void removePutProvider(PutProvider<PE> putProvider) {
    synchronized (providers) {
      boolean removed = providers.remove(putProvider);
      if (!removed) {
        Iterator<PutProvider<PE>> iterator = providers.iterator();
        while (iterator.hasNext()) {
          PutProvider<PE> next = iterator.next();
          if (next == putProvider) {
            iterator.remove();
            System.out.println("special remove applied.");
            return;
          }
        }
        // ---
        System.err.println(putProvider.getClass().getSimpleName());
        new RuntimeException("put provider not removed").printStackTrace();
      }
    }
  }

  /***************************************************/
  public final void addGetListener(GetListener<GE> getListener) {
    synchronized (getListeners) {
      boolean added = getListeners.add(getListener);
      if (!added) {
        System.err.println(getListener.getClass().getSimpleName());
        new RuntimeException("get listener not added").printStackTrace();
      }
    }
  }

  public final void removeGetListener(GetListener<GE> getListener) {
    synchronized (getListeners) {
      boolean removed = getListeners.remove(getListener);
      if (!removed) {
        System.err.println(getListener.getClass().getSimpleName());
        new RuntimeException("get listener not removed").printStackTrace();
      }
    }
  }

  /***************************************************/
  public final void addPutListener(PutListener<PE> putListener) {
    synchronized (providers) {
      boolean added = putListeners.add(putListener);
      if (!added) {
        System.err.println(putListener.getClass().getSimpleName());
        new RuntimeException("put listener not added").printStackTrace();
      }
    }
  }

  public final void removePutListener(PutListener<PE> putListener) {
    synchronized (providers) {
      boolean removed = putListeners.remove(putListener);
      if (!removed) {
        System.err.println(putListener.getClass().getSimpleName());
        new RuntimeException("put listener not removed").printStackTrace();
      }
    }
  }
}

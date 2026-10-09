package io.github.cloraj1.jukebox;

import java.util.concurrent.atomic.AtomicInteger;

/** Worker threads enqueue cues; only the client tick plays Minecraft sounds. */
public final class OperationErrors {
    private final AtomicInteger pending=new AtomicInteger();
    void signal() { pending.incrementAndGet(); }
    public void drain(Runnable play) { for(int count=pending.getAndSet(0);count>0;count--) play.run(); }
}

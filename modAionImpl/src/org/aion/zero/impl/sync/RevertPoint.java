package org.aion.zero.impl.sync;

import java.util.HashSet;
import java.util.Set;

public class RevertPoint {

    public static int REVERT_STEPS = 128;

    public long jump = 0;

    public long beforeJump = 0;

    public Set<Integer> revertPeers = new HashSet<>();

    private boolean isReverting = false;

    public RevertPoint(long j) {
        jump = j;
    }

    public void set(long j) {
        jump = j;
    }

    public long get() {
        return jump;
    }

    public void update(long j) {
        jump = jump < j ? j : jump;
    }

    public void decrease(long j) {
        long gap = j - jump;
        jump = gap > REVERT_STEPS ? jump - REVERT_STEPS : jump;
    }

    public synchronized void setRevertFlat(boolean b) {
        isReverting = b;
    }

    public synchronized boolean isRevert() {
        return isReverting;
    }

    public synchronized boolean isRevertPeer(int id) {
        return revertPeers.contains(id);
    }

    public void setRevertPoint(long l) {
        if (l > beforeJump) {
            beforeJump = l;
        }
    }
}

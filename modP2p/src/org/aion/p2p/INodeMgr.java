package org.aion.p2p;

import java.math.BigInteger;

public interface INodeMgr {
    
    void updateAllNodesInfo(INode _n);

    void updateSelfInfo(BigInteger selfTd, long number, byte[] hash);
}

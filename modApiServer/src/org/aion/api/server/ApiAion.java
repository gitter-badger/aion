/*******************************************************************************
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 *
 ******************************************************************************/

package org.aion.api.server;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.aion.api.server.pb.TxWaitingMappingUpdate;
import org.aion.api.server.types.*;
import org.aion.base.type.*;
import org.aion.evtmgr.IHandler;
import org.aion.evtmgr.impl.callback.EventCallbackA0;
import org.aion.mcf.account.Keystore;

import org.aion.base.util.*;
import org.aion.crypto.ECKey;
import org.aion.evtmgr.IEventMgr;
import org.aion.evtmgr.impl.evt.EventBlock;
import org.aion.evtmgr.impl.evt.EventTx;
import org.aion.mcf.vm.types.Log;
import org.aion.zero.impl.AionGenesis;
import org.aion.zero.impl.blockchain.AionPendingStateImpl;
import org.aion.zero.impl.blockchain.IAionChain;
import org.aion.zero.impl.config.CfgAion;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.impl.types.AionBlockSummary;
import org.aion.zero.impl.types.AionTxInfo;
import org.aion.zero.types.AionTransaction;
import org.aion.zero.types.AionTxReceipt;
import org.aion.zero.types.IAionBlock;
import org.apache.commons.collections4.map.LRUMap;

public abstract class ApiAion extends Api {

    protected IAionChain ac = null;
    private final static short FLTRS_MAX = CfgAion.inst().getApi().getFilter().getSize();
    protected AtomicLong fltrIndex = new AtomicLong(1);
    protected Map<Long, Fltr> installedFilters = null;
    protected Map<ByteArrayWrapper, AionTxReceipt> pendingReceipts;

    private int MAP_SIZE = 50000;
    private LinkedBlockingQueue<TxPendingStatus> txPendingStatus;
    private LinkedBlockingQueue<TxWaitingMappingUpdate> txWait;
    private Map<ByteArrayWrapper, Map.Entry<ByteArrayWrapper, ByteArrayWrapper>> msgIdMapping;

    private boolean activeZmq = CfgAion.inst().getApi().getZmq().getActive();
    private boolean activeWeb3 =  CfgAion.inst().getApi().getRpc().getActive();

    public ApiAion(final IAionChain _ac) {
        this.ac = _ac;
        this.regEvents();

        if (activeZmq) {
            txPendingStatus = new LinkedBlockingQueue<>();
            txWait = new LinkedBlockingQueue<>();
            msgIdMapping = Collections
                    .synchronizedMap(new LRUMap<>(MAP_SIZE, 100));
        }

        if (CfgAion.inst().getApi().getFilter().getActive()) {
            this.installedFilters = new ConcurrentHashMap<>();
        }

        this.pendingReceipts = Collections.synchronizedMap(new LRUMap<>(FLTRS_MAX, 100));

        if (CfgAion.inst().getApi().getFilter().getActive()) {
            blockEvtCallback();
        }

        txEvtCallback();

    }

    private void txEvtCallback() {
        IHandler txHr = this.ac.getAionHub().getEventMgr().getHandler(1);
        if (txHr != null) {
            txHr.eventCallback(
                new EventCallbackA0<IBlock, ITransaction, ITxReceipt, IBlockSummary, ITxExecSummary, ISolution>() {
                    public void onPendingTxUpdate(final ITxReceipt _txRcpt, final EventTx.STATE _state,
                            final IBlock _blk) {
                        ByteArrayWrapper txHashW = new ByteArrayWrapper(
                                ((AionTxReceipt) _txRcpt).getTransaction().getHash());

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("ApiAion.onPendingTxUpdate - txHash: [{}], state: [{}]",
                                    txHashW.toString(), _state.getValue());
                        }

                        if (activeZmq) {
                            if (msgIdMapping.get(txHashW) != null) {
                                if (txPendingStatus.remainingCapacity() == 0) {
                                    txPendingStatus.poll();
                                    LOG.warn(
                                            "ApiAion.onPendingTxUpdate - txPend ingStatus queue full, drop the first message.");
                                }

                                if (LOG.isTraceEnabled()) {
                                    LOG.trace("ApiAion.onPendingTxUpdate - the pending Tx state : [{}]",
                                            _state.getValue());
                                }

                                txPendingStatus.add(new TxPendingStatus(txHashW,
                                        msgIdMapping.get(txHashW).getValue(),
                                        msgIdMapping.get(txHashW).getKey(), _state.getValue(),
                                        ByteArrayWrapper.wrap(((AionTxReceipt) _txRcpt).getExecutionResult() == null
                                                ? ByteUtil.EMPTY_BYTE_ARRAY
                                                : ((AionTxReceipt) _txRcpt).getExecutionResult())));

                                if (!_state.isPending()) {
                                    msgIdMapping.remove(txHashW);
                                }
                            } else {
                                if (txWait.remainingCapacity() == 0) {
                                    txWait.poll();

                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug(
                                                "ApiAion.onPendingTxUpdate - txWait queue full, drop the first message.");
                                    }
                                }

                                // waiting origin Api call status been callback
                                try {
                                    txWait.put(new TxWaitingMappingUpdate(txHashW, _state.getValue(),
                                            ((AionTxReceipt) _txRcpt)));
                                } catch (InterruptedException e) {
                                    LOG.error("ApiAion.onPendingTxUpdate txWait.put exception",
                                            e.getMessage());
                                }
                            }
                        }

                        if (_state.isPending() || _state == EventTx.STATE.DROPPED0) {
                            pendingReceipts.put(txHashW, (AionTxReceipt) _txRcpt);
                        } else {
                            pendingReceipts.remove(txHashW);
                        }
                    }

                    public void onPendingTxReceived(ITransaction _tx) {
                        installedFilters.values().forEach((f) -> {
                            if (f.getType() == Fltr.Type.TRANSACTION) {
                                f.add(new EvtTx((AionTransaction) _tx));
                            }
                        });
                    }
                });
        }
    }

    private void blockEvtCallback() {
        IHandler blkHr = this.ac.getAionHub().getEventMgr().getHandler(2);
        if (blkHr != null) {
            blkHr.eventCallback(
                new EventCallbackA0<IBlock, ITransaction, ITxReceipt, IBlockSummary, ITxExecSummary, ISolution>() {
                    public void onBlock(final IBlockSummary _bs) {

                        AionBlockSummary bs = (AionBlockSummary) _bs;

                        if (activeWeb3) {
                            IAionBlock b = bs.getBlock();
                            List<AionTransaction> txs = b.getTransactionsList();

                        /*
                         * TODO: fix it If dump empty txs list block to
                         * onBlock filter leads null exception on
                         * getTransactionReceipt
                         */
                            if (txs.size() > 0) {
                                installedFilters.values().forEach((f) -> {
                                    switch (f.getType()) {
                                    case BLOCK:
                                        f.add(new EvtBlk(b));
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("<event-new-block num={} txs={}>", b.getNumber(), txs.size());
                                        break;
                                    case LOG:
                                        List<AionTxReceipt> txrs = bs.getReceipts();
                                        int txIndex = 0;
                                        int lgIndex = 0;
                                        for (AionTxReceipt txr : txrs) {
                                            List<Log> infos = txr.getLogInfoList();
                                            for (Log bi : infos) {
                                                TxRecptLg txLg = new TxRecptLg(bi, b, txIndex, txr.getTransaction(),
                                                        lgIndex);
                                                txIndex++;
                                                lgIndex++;
                                                f.add(new EvtLg(txLg));
                                            }
                                        }
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("<event-new-log num={} txs={}>", b.getNumber(), txs.size());
                                        break;
                                    default:
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("<event-new-", b.getNumber(), txs.size());
                                        break;
                                    }
                                });
                            }
                        }

                        if (activeZmq) {
                            Set<Long> keys = installedFilters.keySet();
                            for (Long key : keys) {
                                Fltr fltr = installedFilters.get(key);
                                if (fltr.isExpired()) {
                                    LOG.debug("<fltr key={} expired removed>", key);
                                    installedFilters.remove(key);
                                } else {
                                    @SuppressWarnings("unchecked")
                                    List<AionTxReceipt> txrs = bs.getReceipts();
                                    if (fltr.getType() == Fltr.Type.EVENT
                                            && !Optional.ofNullable(txrs).orElse(Collections.emptyList()).isEmpty()) {
                                        FltrCt _fltr = (FltrCt) fltr;

                                        for (AionTxReceipt txr : txrs) {
                                            AionTransaction tx = txr.getTransaction();
                                            Address contractAddress = Optional.ofNullable(tx.getTo())
                                                    .orElse(tx.getContractAddress());

                                            Integer cnt = 0;
                                            txr.getLogInfoList().forEach(bi -> bi.getTopics().forEach(lg -> {
                                                if (_fltr.isFor(contractAddress, ByteUtil.toHexString(lg))) {
                                                    IBlock<AionTransaction, ?> blk = bs.getBlock();
                                                    List<AionTransaction> txList = blk.getTransactionsList();
                                                    int insideCnt = 0;
                                                    for (AionTransaction t : txList) {
                                                        if (Arrays.equals(t.getHash(), tx.getHash())) {
                                                            break;
                                                        }
                                                        insideCnt++;
                                                    }

                                                    EvtContract ec = new EvtContract(bi.getAddress().toBytes(),
                                                            bi.getData(), blk.getHash(), blk.getNumber(), cnt,
                                                            ByteUtil.toHexString(lg), false, insideCnt, tx.getHash());

                                                    _fltr.add(ec);
                                                }
                                            }));
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
        }
    }

    // General Level
    public byte getApiVersion() {
        return 2;
    }

    // --Commented out by Inspection START (02/02/18 6:57 PM):
    // public int getProtocolVersion() {
    // return 0;
    // }
    // --Commented out by Inspection STOP (02/02/18 6:57 PM)

    protected Map<Long, Fltr> getInstalledFltrs() {
        return installedFilters;
    }

    // Authenication Level
    public String getCoinbase() {
        String coinbase = CfgAion.inst().getConsensus().getMinerAddress();
        if (Address.wrap(coinbase).equals(Address.EMPTY_ADDRESS())) { // no
                                                                      // miner
                                                                      // coinbase
                                                                      // set
            List<String> accsSorted = Keystore.accountsSorted();
            if (accsSorted.isEmpty()) {
                return TypeConverter.toJsonHex("");
            }
            String cb = accsSorted.get(0);
            return TypeConverter.toJsonHex(cb);
        }
        return TypeConverter.toJsonHex(coinbase);
    }

    // Chain Level
    @Override
    public AionBlock getBestBlock() {
        return this.ac.getAionHub().getBlockchain().getBestBlock();
    }

    public AionBlock getBlockTemplate() {
        // TODO: Change to follow onBlockTemplate event mode defined in internal
        // miner
        // TODO: Track multiple block templates
        AionBlock bestPendingState = ((AionPendingStateImpl) ac.getAionHub().getPendingState()).getBestBlock();

        AionPendingStateImpl.TransactionSortedSet ret = new AionPendingStateImpl.TransactionSortedSet();
        ret.addAll(ac.getAionHub().getPendingState().getPendingTransactions());

        return ac.getAionHub().getBlockchain().createNewBlock(bestPendingState, new ArrayList<>(ret), false);
    }

    // --Commented out by Inspection START (02/02/18 6:57 PM):
    // @Override
    // public AionBlock getBlock(String _bnOrId) {
    // long bn = this.parseBnOrId(_bnOrId);
    // return this.ac.getAionHub().getBlockchain().getBlockByNumber(bn);
    // }
    // --Commented out by Inspection STOP (02/02/18 6:57 PM)

    public AionBlock getBlockByHash(byte[] hash) {
        return this.ac.getAionHub().getBlockchain().getBlockByHash(hash);
    }

    @Override
    public AionBlock getBlock(long blkNr) {
        if (blkNr == -1) {
            return this.ac.getAionHub().getBlockchain()
                    .getBlockByNumber(this.ac.getBlockchain().getBestBlock().getNumber());
        } else if (blkNr > 0) {
            return this.ac.getAionHub().getBlockchain().getBlockByNumber(blkNr);
        } else if (blkNr == 0) {
            AionGenesis genBlk = CfgAion.inst().getGenesis();
            return new AionBlock(genBlk.getHeader(), genBlk.getTransactionsList());
        } else {
            LOG.debug("ApiAion.getBlock - incorrect argument");
            return null;
        }
    }

    public SyncInfo getSync() {
        SyncInfo sync = new SyncInfo();
        sync.done = this.ac.isSyncComplete();
        sync.chainStartingBlkNumber = this.ac.getInitialStartingBlockNumber().orElse(0L);
        sync.blksImportMax = CfgAion.inst().getSync().getBlocksImportMax();
        sync.networkBestBlkNumber = this.ac.getNetworkBestBlockNumber().orElse(0L);
        sync.chainBestBlkNumber = this.ac.getLocalBestBlockNumber().orElse(0L);
        return sync;
    }

    protected AionTransaction getTransactionByBlockHashAndIndex(byte[] hash, long index) {
        AionBlock pBlk = this.getBlockByHash(hash);
        if (pBlk == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("ApiAion.getTransactionByBlockHashAndIndex - can't find the block by the block hash");
            }
            return null;
        }

        List<AionTransaction> txList = pBlk.getTransactionsList();
        AionTransaction tx = txList.get((int) index);
        if (tx == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Can't find the transaction!");
            }
            return null;
        }

        TxRecpt receipt = this.getTransactionReceipt(tx.getHash());
        // @Jay this should not happen!
        // TODO
        if (receipt == null) {
            throw new NullPointerException();
        }

        tx.setBlockNumber(pBlk.getNumber());
        tx.setBlockHash(pBlk.getHash());
        tx.setTxIndexInBlock(index);
        tx.setNrgConsume(receipt.nrgUsed);
        return tx;
    }

    protected AionTransaction getTransactionByBlockNumberAndIndex(long blkNr, long index) {
        AionBlock pBlk = this.getBlock(blkNr);
        if (pBlk == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("ApiAion.getTransactionByBlockNumberAndIndex - can't find the block by the block number");
            }
            return null;
        }

        List<AionTransaction> txList = pBlk.getTransactionsList();
        AionTransaction tx = txList.get((int) index);
        if (tx == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Can't find the transaction by the txIndex");
            }
            return null;
        }

        TxRecpt receipt = this.getTransactionReceipt(tx.getHash());
        // The receipt shouldn't be null!
        if (receipt == null) {
            throw new NullPointerException();
        }

        tx.rlpParse();
        tx.setBlockNumber(pBlk.getNumber());
        tx.setBlockHash(pBlk.getHash());
        tx.setTxIndexInBlock(index);
        tx.setNrgConsume(receipt.nrgUsed);
        return tx;
    }

    protected long getBlockTransactionCountByNumber(long blkNr) {
        AionBlock pBlk = this.getBlock(blkNr);
        if (pBlk == null) {
            LOG.error("ApiAion.getTransactionByBlockNumberAndIndex - can't find the block by the block number");
            return -1;
        }

        return pBlk.getTransactionsList().size();
    }

    protected long getTransactionCountByHash(byte[] hash) {
        AionBlock pBlk = this.getBlockByHash(hash);
        if (pBlk == null) {
            LOG.error("ApiAion.getTransactionByBlockNumberAndIndex - can't find the block by the block number");
            return -1;
        }
        return pBlk.getTransactionsList().size();
    }

    protected long getTransactionCount(Address addr, long blkNr) {
        AionBlock pBlk = this.getBlock(blkNr);
        if (pBlk == null) {
            LOG.error("ApiAion.getTransactionByBlockNumberAndIndex - can't find the block by the block number");
            return -1;
        }
        long cnt = 0;
        List<AionTransaction> txList = pBlk.getTransactionsList();
        for (AionTransaction tx : txList) {
            if (addr.equals(tx.getFrom())) {
                cnt++;
            }
        }
        return cnt;
    }

    protected AionTransaction getTransactionByHash(byte[] hash) {
        TxRecpt txRecpt = this.getTransactionReceipt(hash);

        if (txRecpt == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Can't find the transaction receipt by the txhash.");
            }
            return null;
        } else {
            AionTransaction atx = this.getTransactionByBlockNumberAndIndex(txRecpt.blockNumber,
                    txRecpt.transactionIndex);

            if (atx == null) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Can't find the transaction by the blocknumber and the txIndex.");
                }
                return null;
            }

            atx.setNrgConsume(txRecpt.nrgUsed);
            return atx;
        }
    }

    protected byte[] getCode(Address addr) {
        return this.ac.getRepository().getCode(addr);
    }

    protected TxRecpt getTransactionReceipt(byte[] txHash) {
        if (txHash == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("<get-transaction-receipt msg=tx-hash-null>");
            }
            return null;
        }

        AionTxInfo txInfo = this.ac.getAionHub().getBlockchain().getTransactionInfo(txHash);
        if (txInfo == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("<get-transaction-receipt msg=tx-info-null>");
            }
            return null;
        }
        AionBlock block = this.ac.getAionHub().getBlockchain().getBlockByHash(txInfo.getBlockHash());

        if (block == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("<get-transaction-receipt msg=block-null>");
            }
            return null;
        }

        // need to return txes only from main chain
        AionBlock mainBlock = this.ac.getAionHub().getBlockchain().getBlockByNumber(block.getNumber());
        if (!Arrays.equals(block.getHash(), mainBlock.getHash())) {
            LOG.debug("<get-transaction-receipt msg=hash-not-match>");
            return null;
        }

        // @Jay
        // TODO : think the good way to calculate the cumulated nrg use
        long cumulateNrg = 0L;
        for (AionTransaction atx : block.getTransactionsList()) {

            // @Jay: This should not happen!
            byte[] hash = atx.getHash();
            if (hash == null) {
                throw new NullPointerException();
            }

            AionTxInfo info = this.ac.getAionHub().getBlockchain().getTransactionInfo(hash);

            // @Jay: This should not happen!
            if (info == null) {
                throw new NullPointerException();
            }

            cumulateNrg += info.getReceipt().getEnergyUsed();
            if (Arrays.equals(txHash, hash)) {
                break;
            }
        }

        return new TxRecpt(block, txInfo, cumulateNrg);
    }

    public byte[] doCall(ArgTxCall _params) {
        AionTransaction tx = new AionTransaction(_params.getNonce().toByteArray(), _params.getTo(),
                _params.getValue().toByteArray(), _params.getData(), _params.getNrg(), _params.getNrgPrice());
        AionTxReceipt rec = this.ac.callConstant(tx, this.ac.getAionHub().getBlockchain().getBestBlock());
        return rec.getExecutionResult();
    }

    public long estimateGas(ArgTxCall params) {
        AionTransaction tx = new AionTransaction(params.getNonce().toByteArray(), params.getTo(),
                params.getValue().toByteArray(), params.getData(), params.getNrg(), params.getNrgPrice());
        AionTxReceipt receipt = this.ac.callConstant(tx, this.ac.getAionHub().getBlockchain().getBestBlock());
        return receipt.getEnergyUsed();
    }

    protected ContractCreateResult createContract(ArgTxCall _params) {

        Address from = _params.getFrom();

        if (from == null || from.equals(Address.EMPTY_ADDRESS())) {
            return null;
        }

        ECKey key = this.getAccountKey(from.toString());

        if (key == null) {
            LOG.debug("ApiAion.createContract - null key");
            return null;
        } else {
            try {
                synchronized (pendingState) {
                    byte[] nonce = !(_params.getNonce().equals(BigInteger.ZERO)) ? _params.getNonce().toByteArray()
                            : pendingState.bestNonce(Address.wrap(key.getAddress())).toByteArray();

                    AionTransaction tx = new AionTransaction(nonce, from, null, _params.getValue().toByteArray(),
                            _params.getData(), _params.getNrg(), _params.getNrgPrice());
                    tx.sign(key);

                    pendingState.addPendingTransaction(tx);

                    ContractCreateResult c = new ContractCreateResult();
                    c.address = tx.getContractAddress();
                    c.transId = tx.getHash();
                    return c;
                }
            } catch (Exception ex) {
                LOG.error("ApiAion.createContract - exception: [{}]", ex.getMessage());

                return null;
            }
        }

    }

    // Transaction Level
    public BigInteger getBalance(String _address) throws Exception {
        return this.ac.getRepository().getBalance(Address.wrap(_address));
    }

    protected BigInteger getBalance(Address _address) {
        return this.ac.getRepository().getBalance(_address);
    }

    protected long estimateNrg(ArgTxCall _params) {
        if (_params == null) {
            throw new NullPointerException();
        }

        Address from = _params.getFrom();

        if (from.equals(Address.EMPTY_ADDRESS())) {
            LOG.error("<send-transaction msg=invalid-from-address>");
            return -1L;
        }

        ECKey key = this.getAccountKey(from.toString());
        if (key == null) {
            LOG.error("<send-transaction msg=account-not-found>");
            return -1L;
        }

        try {
            // Transaction is executed as local transaction, no need to retrieve the real nonce.
            byte[] nonce = BigInteger.ZERO.toByteArray();

            AionTransaction tx = new AionTransaction(nonce, _params.getTo(), _params.getValue().toByteArray(),
                    _params.getData(), _params.getNrg(), _params.getNrgPrice());
            tx.sign(key);

            return this.ac.estimateTxNrg(tx, this.ac.getAionHub().getBlockchain().getBestBlock());
        } catch (Exception ex) {
            return -1L;
        }
    }

    public byte[] sendTransaction(ArgTxCall _params) {

        Address from = _params.getFrom();

        if (from == null || from.equals(Address.EMPTY_ADDRESS())) {
            LOG.error("<send-transaction msg=invalid-from-address>");
            return ByteUtil.EMPTY_BYTE_ARRAY;
        }

        ECKey key = this.getAccountKey(from.toString());
        if (key == null) {
            LOG.error("<send-transaction msg=account-not-found>");
            return ByteUtil.EMPTY_BYTE_ARRAY;
        }

        try {
            synchronized (pendingState) {
                // TODO : temp set nrg & price to 1
                byte[] nonce = (!_params.getNonce().equals(BigInteger.ZERO)) ? _params.getNonce().toByteArray()
                        : pendingState.bestNonce(Address.wrap(key.getAddress())).toByteArray();

                AionTransaction tx = new AionTransaction(nonce, _params.getTo(), _params.getValue().toByteArray(),
                        _params.getData(), _params.getNrg(), _params.getNrgPrice());
                tx.sign(key);

                pendingState.addPendingTransaction(tx);

                return tx.getHash();
            }
        } catch (Exception ex) {
            return ByteUtil.EMPTY_BYTE_ARRAY;
        }
    }

    public byte[] sendTransaction(byte[] signedTx) {
        if (signedTx == null) {
            throw new NullPointerException();
        }

        try {
            AionTransaction tx = new AionTransaction(signedTx);

            pendingState.addPendingTransaction(tx);

            return tx.getHash();
        } catch (Exception ex) {
            return ByteUtil.EMPTY_BYTE_ARRAY;
        }
    }

    // --Commented out by Inspection START (02/02/18 6:58 PM):
    // public String getNodeId() {
    // return CfgAion.inst().getId();
    // }
    // --Commented out by Inspection STOP (02/02/18 6:58 PM)

    protected String[] getBootNodes() {
        return CfgAion.inst().getNodes();
    }

//    private synchronized BigInteger getTxNonce(ECKey key) {
//        return pendingState.bestNonce();
//    }

//    private synchronized BigInteger getTxNonce(ECKey key, boolean add) {
//        return add ? nm.getNonceAndAdd(Address.wrap(key.getAddress())) : nm.getNonce(Address.wrap(key.getAddress()));
//    }

    private void regEvents() {
        IEventMgr evtMgr = this.ac.getAionHub().getEventMgr();
        regTxEvents(evtMgr);

        if (CfgAion.inst().getApi().getFilter().getActive()) {
            regBlkEvents(evtMgr);
        }
    }

    /**
     * @param evtMgr
     *            Oct 12, 2017 jay void
     */
    private void regBlkEvents(final IEventMgr evtMgr) {
        evtMgr.registerEvent(Collections.singletonList(new EventBlock(EventBlock.CALLBACK.ONBLOCK0)));
    }

    /**
     * @param evtMgr
     *            Oct 12, 2017 jay void
     */
    private void regTxEvents(final IEventMgr evtMgr) {
        evtMgr.registerEvent(Collections.singletonList(new EventTx(EventTx.CALLBACK.PENDINGTXUPDATE0)));
    }

    protected boolean isMining() {
        return this.ac.getBlockMiner().isMining();
    }

    public int peerCount() {
        return this.ac.getAionHub().getP2pMgr().getActiveNodes().size();
    }

    protected LinkedBlockingQueue<TxWaitingMappingUpdate> getTxWait() {
        return txWait;
    }

    protected LinkedBlockingQueue<TxPendingStatus> getTxPendingStatus() {
        return txPendingStatus;
    }

    protected Map<ByteArrayWrapper, Map.Entry<ByteArrayWrapper, ByteArrayWrapper>> getMsgIdMapping() {
        return msgIdMapping;
    }
}

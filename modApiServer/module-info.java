module aion.apiserver {

    requires aion.base;
    requires aion.zero.impl;
    requires aion.log;
    requires aion.p2p;
    requires aion.zero;
    requires aion.mcf;
    requires aion.crypto;
    requires slf4j.api;
    requires aion.evtmgr;
    requires aion.evtmgr.impl;

    exports org.aion.api.server.pb;
    exports org.aion.api.server.http;
    exports org.aion.api.server.zmq;
}
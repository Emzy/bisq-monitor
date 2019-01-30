/*
 * This file is part of Bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.monitor.metric;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.jetbrains.annotations.NotNull;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import bisq.common.app.Version;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.core.proto.network.CoreNetworkProtoResolver;
import bisq.monitor.AvailableTor;
import bisq.monitor.Metric;
import bisq.monitor.Monitor;
import bisq.monitor.OnionParser;
import bisq.monitor.Reporter;
import bisq.monitor.StatisticsHelper;
import bisq.monitor.ThreadGate;
import bisq.network.p2p.CloseConnectionMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.MessageListener;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.network.SetupListener;
import bisq.network.p2p.network.TorNetworkNode;
import bisq.network.p2p.peers.keepalive.messages.Ping;
import bisq.network.p2p.peers.keepalive.messages.Pong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PRoundTripTime extends Metric implements MessageListener, SetupListener {

    private static final String SAMPLE_SIZE = "run.sampleSize";
    private static final String HOSTS = "run.hosts";
    private static final String TOR_PROXY_PORT = "run.torProxyPort";
    private NetworkNode networkNode;
    private final File torHiddenServiceDir = new File("metric_p2pRoundTripTime");
    private int nonce;
    private long start;
    private List<Long> samples;
    private final ThreadGate gate = new ThreadGate();
    private final ThreadGate hsReady = new ThreadGate();

    public P2PRoundTripTime(Reporter reporter) {
        super(reporter);

        Version.setBaseCryptoNetworkId(0); // set to BTC_MAINNET
    }

    @Override
    public void configure(Properties properties) {
        super.configure(properties);
    }

    @Override
    protected void execute() {

        if (null == networkNode) {
            // close the gate
            hsReady.engage();

            networkNode = new TorNetworkNode(Integer.parseInt(configuration.getProperty(TOR_PROXY_PORT, "9052")),
                    new CoreNetworkProtoResolver(), false,
                    new AvailableTor(Monitor.TOR_WORKING_DIR, torHiddenServiceDir.getName()));
            networkNode.start(this);

            // wait for the gate to be reopened
            hsReady.await();
        }


        // for each configured host
        for (String current : configuration.getProperty(HOSTS, "").split(",")) {
            try {
                // parse Url
                NodeAddress target = OnionParser.getNodeAddress(current);

                // init sample bucket
                samples = new ArrayList<>();

                while (samples.size() < Integer.parseInt(configuration.getProperty(SAMPLE_SIZE, "1"))) {
                    // so we do not get disconnected due to DoS protection mechanisms
                    Thread.sleep(1000);

                    nonce = new Random().nextInt();

                    // close the gate
                    gate.engage();

                    start = System.currentTimeMillis();
                    SettableFuture<Connection> future = networkNode.sendMessage(target, new Ping(nonce, 42));

                    Futures.addCallback(future, new FutureCallback<Connection>() {
                        @Override
                        public void onSuccess(Connection connection) {
                            connection.addMessageListener(P2PRoundTripTime.this);
                            log.debug("Send ping to " + connection + " succeeded.");
                        }

                        @Override
                        public void onFailure(@NotNull Throwable throwable) {
                            log.error("Sending ping failed. That is expected if the peer is offline.\n\tException="
                                    + throwable.getMessage());
                        }
                    });

                    // wait for the gate to open again
                    gate.await();
                }

                // report
                reporter.report(StatisticsHelper.process(samples),
                        "bisq." + getName() + "." + OnionParser.prettyPrint(target));
            } catch (Exception e) {
                gate.proceed(); // release the gate on error
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof Pong) {
            Pong pong = (Pong) networkEnvelope;
            if (pong.getRequestNonce() == nonce) {
                samples.add(System.currentTimeMillis() - start);
            } else {
                log.warn("Nonce not matching. That should never happen.\n\t" +
                                "We drop that message. nonce={} / requestNonce={}",
                        nonce, pong.getRequestNonce());
            }
            connection.removeMessageListener(this);

            // open the gate
            gate.proceed();
        } else if (networkEnvelope instanceof CloseConnectionMessage) {
            gate.unlock();
        } else {
            log.warn("Got a message of type <{}>, expected <Pong>", networkEnvelope.getClass().getSimpleName());
        }
    }

    @Override
    public void onTorNodeReady() {
        // TODO Auto-generated method stub
    }

    @Override
    public void onHiddenServicePublished() {
        hsReady.proceed();
    }

    @Override
    public void onSetupFailed(Throwable throwable) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onRequestCustomBridges() {
        // TODO Auto-generated method stub

    }
}

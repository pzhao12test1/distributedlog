/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.distributedlog.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import org.apache.distributedlog.DLMTestUtil;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.client.DistributedLogClientImpl;
import org.apache.distributedlog.client.resolver.DefaultRegionResolver;
import org.apache.distributedlog.client.routing.LocalRoutingService;
import org.apache.distributedlog.client.routing.RegionsRoutingService;
import org.apache.distributedlog.service.DistributedLogCluster.DLServer;
import org.apache.distributedlog.service.stream.StreamManager;
import org.apache.distributedlog.service.stream.StreamManagerImpl;
import com.twitter.finagle.builder.ClientBuilder;
import com.twitter.finagle.thrift.ClientId$;
import com.twitter.util.Duration;
import java.net.SocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

/**
 * Base test case for distributedlog servers.
 */
public abstract class DistributedLogServerTestCase {

    static {
        // org.apache.zookeeper.test.ClientBase uses FourLetterWordMain, from 3.5.3 four letter words
        // are disabled by default due to security reasons
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
    }

    protected static DistributedLogConfiguration conf =
            new DistributedLogConfiguration().setLockTimeout(10)
                    .setOutputBufferSize(0).setPeriodicFlushFrequencyMilliSeconds(10);
    protected static DistributedLogConfiguration noAdHocConf =
            new DistributedLogConfiguration().setLockTimeout(10).setCreateStreamIfNotExists(false)
                    .setOutputBufferSize(0).setPeriodicFlushFrequencyMilliSeconds(10);
    protected static DistributedLogCluster dlCluster;
    protected static DistributedLogCluster noAdHocCluster;

    /**
     * A distributedlog client wrapper for testing.
     */
    protected static class DLClient {
        public final LocalRoutingService routingService;
        public DistributedLogClientBuilder dlClientBuilder;
        public final DistributedLogClientImpl dlClient;

        protected DLClient(String name,
                           String streamNameRegex,
                           Optional<String> serverSideRoutingFinagleName) {
            routingService = LocalRoutingService.newBuilder().build();
            dlClientBuilder = DistributedLogClientBuilder.newBuilder()
                        .name(name)
                        .clientId(ClientId$.MODULE$.apply(name))
                        .routingService(routingService)
                        .streamNameRegex(streamNameRegex)
                        .handshakeWithClientInfo(true)
                        .clientBuilder(ClientBuilder.get()
                            .hostConnectionLimit(1)
                            .connectionTimeout(Duration.fromSeconds(1))
                            .requestTimeout(Duration.fromSeconds(60)));
            if (serverSideRoutingFinagleName.isPresent()) {
                dlClientBuilder =
                        dlClientBuilder.serverRoutingServiceFinagleNameStr(serverSideRoutingFinagleName.get());
            }
            dlClient = (DistributedLogClientImpl) dlClientBuilder.build();
        }

        public void handshake() {
            dlClient.handshake();
        }

        public void shutdown() {
            dlClient.close();
        }
    }

    /**
     * A distributedlog client wrapper that talks to two regions.
     */
    protected static class TwoRegionDLClient {

        public final LocalRoutingService localRoutingService;
        public final LocalRoutingService remoteRoutingService;
        public final DistributedLogClientBuilder dlClientBuilder;
        public final DistributedLogClientImpl dlClient;

        protected TwoRegionDLClient(String name, Map<SocketAddress, String> regionMap) {
            localRoutingService = new LocalRoutingService();
            remoteRoutingService = new LocalRoutingService();
            RegionsRoutingService regionsRoutingService =
                    RegionsRoutingService.of(new DefaultRegionResolver(regionMap),
                            localRoutingService, remoteRoutingService);
            dlClientBuilder = DistributedLogClientBuilder.newBuilder()
                        .name(name)
                        .clientId(ClientId$.MODULE$.apply(name))
                        .routingService(regionsRoutingService)
                        .streamNameRegex(".*")
                        .handshakeWithClientInfo(true)
                        .maxRedirects(2)
                        .clientBuilder(ClientBuilder.get()
                            .hostConnectionLimit(1)
                            .connectionTimeout(Duration.fromSeconds(1))
                            .requestTimeout(Duration.fromSeconds(10)));
            dlClient = (DistributedLogClientImpl) dlClientBuilder.build();
        }

        public void shutdown() {
            dlClient.close();
        }
    }

    private final boolean clientSideRouting;
    protected DLServer dlServer;
    protected DLClient dlClient;
    protected DLServer noAdHocServer;
    protected DLClient noAdHocClient;

    public static DistributedLogCluster createCluster(DistributedLogConfiguration conf) throws Exception {
        return DistributedLogCluster.newBuilder()
            .numBookies(3)
            .shouldStartZK(true)
            .zkServers("127.0.0.1")
            .shouldStartProxy(false)
            .dlConf(conf)
            .bkConf(DLMTestUtil.loadTestBkConf())
            .build();
    }

    @BeforeClass
    public static void setupCluster() throws Exception {
        dlCluster = createCluster(conf);
        dlCluster.start();
    }

    public void setupNoAdHocCluster() throws Exception {
        noAdHocCluster = createCluster(noAdHocConf);
        noAdHocCluster.start();
        noAdHocServer = new DLServer(noAdHocConf, noAdHocCluster.getUri(), 7002, false);
        Optional<String> serverSideRoutingFinagleName = Optional.absent();
        if (!clientSideRouting) {
            serverSideRoutingFinagleName =
                    Optional.of("inet!" + DLSocketAddress.toString(noAdHocServer.getAddress()));
        }
        noAdHocClient = createDistributedLogClient("no-ad-hoc-client", serverSideRoutingFinagleName);
    }

    public void tearDownNoAdHocCluster() throws Exception {
        if (null != noAdHocClient) {
            noAdHocClient.shutdown();
        }
        if (null != noAdHocServer) {
            noAdHocServer.shutdown();
        }
    }

    @AfterClass
    public static void teardownCluster() throws Exception {
        if (null != dlCluster) {
            dlCluster.stop();
        }
        if (null != noAdHocCluster) {
            noAdHocCluster.stop();
        }
    }

    protected static URI getUri() {
        return dlCluster.getUri();
    }

    protected DistributedLogServerTestCase(boolean clientSideRouting) {
        this.clientSideRouting = clientSideRouting;
    }

    @Before
    public void setup() throws Exception {
        dlServer = createDistributedLogServer(7001);
        Optional<String> serverSideRoutingFinagleName = Optional.absent();
        if (!clientSideRouting) {
            serverSideRoutingFinagleName =
                    Optional.of("inet!" + DLSocketAddress.toString(dlServer.getAddress()));
        }
        dlClient = createDistributedLogClient("test", serverSideRoutingFinagleName);
    }

    @After
    public void teardown() throws Exception {
        if (null != dlClient) {
            dlClient.shutdown();
        }
        if (null != dlServer) {
            dlServer.shutdown();
        }
    }

    protected DLServer createDistributedLogServer(int port) throws Exception {
        return new DLServer(conf, dlCluster.getUri(), port, false);
    }

    protected DLServer createDistributedLogServer(DistributedLogConfiguration conf, int port)
            throws Exception {
        return new DLServer(conf, dlCluster.getUri(), port, false);
    }

    protected DLClient createDistributedLogClient(String clientName,
                                                  Optional<String> serverSideRoutingFinagleName)
            throws Exception {
        return createDistributedLogClient(clientName, ".*", serverSideRoutingFinagleName);
    }

    protected DLClient createDistributedLogClient(String clientName,
                                                  String streamNameRegex,
                                                  Optional<String> serverSideRoutingFinagleName)
            throws Exception {
        return new DLClient(clientName, streamNameRegex, serverSideRoutingFinagleName);
    }

    protected TwoRegionDLClient createTwoRegionDLClient(String clientName,
                                                        Map<SocketAddress, String> regionMap)
            throws Exception {
        return new TwoRegionDLClient(clientName, regionMap);
    }

    protected static void checkStreams(int numExpectedStreams, DLServer dlServer) {
        StreamManager streamManager = dlServer.dlServer.getKey().getStreamManager();
        assertEquals(numExpectedStreams, streamManager.numCached());
        assertEquals(numExpectedStreams, streamManager.numAcquired());
    }

    protected static void checkStreams(Set<String> streams, DLServer dlServer) {
        StreamManagerImpl streamManager = (StreamManagerImpl) dlServer.dlServer.getKey().getStreamManager();
        Set<String> cachedStreams = streamManager.getCachedStreams().keySet();
        Set<String> acquiredStreams = streamManager.getAcquiredStreams().keySet();

        assertEquals(streams.size(), cachedStreams.size());
        assertEquals(streams.size(), acquiredStreams.size());
        assertTrue(Sets.difference(streams, cachedStreams).isEmpty());
        assertTrue(Sets.difference(streams, acquiredStreams).isEmpty());
    }

    protected static void checkStream(String name, DLClient dlClient, DLServer dlServer,
                                      int expectedNumProxiesInClient, int expectedClientCacheSize,
                                      int expectedServerCacheSize, boolean existedInServer, boolean existedInClient) {
        Map<SocketAddress, Set<String>> distribution = dlClient.dlClient.getStreamOwnershipDistribution();
        assertEquals(expectedNumProxiesInClient, distribution.size());

        if (expectedNumProxiesInClient > 0) {
            Map.Entry<SocketAddress, Set<String>> localEntry =
                    distribution.entrySet().iterator().next();
            assertEquals(dlServer.getAddress(), localEntry.getKey());
            assertEquals(expectedClientCacheSize, localEntry.getValue().size());
            assertEquals(existedInClient, localEntry.getValue().contains(name));
        }

        StreamManagerImpl streamManager = (StreamManagerImpl) dlServer.dlServer.getKey().getStreamManager();
        Set<String> cachedStreams = streamManager.getCachedStreams().keySet();
        Set<String> acquiredStreams = streamManager.getCachedStreams().keySet();

        assertEquals(expectedServerCacheSize, cachedStreams.size());
        assertEquals(existedInServer, cachedStreams.contains(name));
        assertEquals(expectedServerCacheSize, acquiredStreams.size());
        assertEquals(existedInServer, acquiredStreams.contains(name));
    }

    protected static Map<SocketAddress, Set<String>> getStreamOwnershipDistribution(DLClient dlClient) {
        return dlClient.dlClient.getStreamOwnershipDistribution();
    }

    protected static Set<String> getAllStreamsFromDistribution(Map<SocketAddress, Set<String>> distribution) {
        Set<String> allStreams = new HashSet<String>();
        for (Map.Entry<SocketAddress, Set<String>> entry : distribution.entrySet()) {
            allStreams.addAll(entry.getValue());
        }
        return allStreams;
    }

}

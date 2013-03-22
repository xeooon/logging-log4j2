/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.flume.appender;

import com.google.common.base.Preconditions;
import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.NettyServer;
import org.apache.avro.ipc.Responder;
import org.apache.avro.ipc.specific.SpecificResponder;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.avro.AvroFlumeEvent;
import org.apache.flume.source.avro.AvroSourceProtocol;
import org.apache.flume.source.avro.Status;
import org.apache.logging.log4j.EventLogger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.XMLConfigurationFactory;
import org.apache.logging.log4j.message.StructuredDataMessage;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 *
 */
public class FlumePersistentAppenderTest {
    private static final String CONFIG = "persistent.xml";
    private static final String HOSTNAME = "localhost";
    private static LoggerContext ctx;

    private EventCollector primary;
    private EventCollector alternate;

    @BeforeClass
    public static void setupClass() {
        // System.setProperty(DefaultConfiguration.DEFAULT_LEVEL, Level.DEBUG.toString());
        final File file = new File("target/file-channel");
        if (!deleteFiles(file)) {
            System.err.println("Warning - unable to delete target/file-channel. Test errors may occur");
        }
    }

    @AfterClass
    public static void cleanupClass() {
        StatusLogger.getLogger().reset();
    }

    @Before
    public void setUp() throws Exception {

        final File file = new File("target/persistent");
        final boolean result = deleteFiles(file);

        /*
        * Clear out all other appenders associated with this logger to ensure we're
        * only hitting the Avro appender.
        */
        int[] ports = findFreePorts(2);
        System.setProperty("primaryPort", Integer.toString(ports[0]));
        System.setProperty("alternatePort", Integer.toString(ports[1]));
        primary = new EventCollector(ports[0]);
        alternate = new EventCollector(ports[1]);
        System.setProperty(XMLConfigurationFactory.CONFIGURATION_FILE_PROPERTY, CONFIG);
        ctx = (LoggerContext) LogManager.getContext(false);
        ctx.reconfigure();
    }

    @After
    public void teardown() throws Exception {
        System.clearProperty(XMLConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
        ctx.reconfigure();
        primary.stop();
        alternate.stop();
        final File file = new File("target/file-channel");
        final boolean result = deleteFiles(file);
        final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        final Set<ObjectName> names = server.queryNames(new ObjectName("org.apache.flume.*:*"), null);
        for (final ObjectName name : names) {
            try {
                server.unregisterMBean(name);
            } catch (final Exception ex) {
                System.out.println("Unable to unregister " + name.toString());
            }
        }
    }

    @Test
    public void testLog4Event() throws InterruptedException, IOException {

        final StructuredDataMessage msg = new StructuredDataMessage("Test", "Test Log4j", "Test");
        EventLogger.logEvent(msg);

        final Event event = primary.poll();
        Assert.assertNotNull(event);
        final String body = getBody(event);
        Assert.assertTrue("Channel contained event, but not expected message. Received: " + body,
            body.endsWith("Test Log4j"));
    }

    @Test
    public void testMultiple() throws InterruptedException, IOException {

        for (int i = 0; i < 10; ++i) {
            final StructuredDataMessage msg = new StructuredDataMessage("Test", "Test Multiple " + i, "Test");
            msg.put("counter", Integer.toString(i));
            EventLogger.logEvent(msg);
        }
        boolean[] fields = new boolean[10];
        for (int i = 0; i < 10; ++i) {
            final Event event = primary.poll();
            Assert.assertNotNull("Received " + i + " events. Event " + (i + 1) + " is null", event);
            final String value = event.getHeaders().get("counter");
            Assert.assertNotNull("Missing counter", value);
            final int counter = Integer.parseInt(value);
            if (fields[counter]) {
                Assert.fail("Duplicate event");
            } else {
                fields[counter] = true;
            }
        }
        for (int i = 0; i < 10; ++i) {
            Assert.assertTrue("Channel contained event, but not expected message " + i, fields[i]);
        }
    }


    @Test
    public void testFailover() throws InterruptedException, IOException {
        final Logger logger = LogManager.getLogger("testFailover");
        logger.debug("Starting testFailover");
        for (int i = 0; i < 10; ++i) {
            final StructuredDataMessage msg = new StructuredDataMessage("Test", "Test Primary " + i, "Test");
            msg.put("counter", Integer.toString(i));
            EventLogger.logEvent(msg);
        }
        boolean[] fields = new boolean[10];
        for (int i = 0; i < 10; ++i) {
            final Event event = primary.poll();
            Assert.assertNotNull("Received " + i + " events. Event " + (i + 1) + " is null", event);
            final String value = event.getHeaders().get("counter");
            Assert.assertNotNull("Missing counter", value);
            final int counter = Integer.parseInt(value);
            if (fields[counter]) {
                Assert.fail("Duplicate event");
            } else {
                fields[counter] = true;
            }
        }
        for (int i = 0; i < 10; ++i) {
            Assert.assertTrue("Channel contained event, but not expected message " + i, fields[i]);
        }

        // Give the AvroSink time to receive notification and notify the channel.
        Thread.sleep(500);

        primary.stop();

        for (int i = 0; i < 10; ++i) {
            final StructuredDataMessage msg = new StructuredDataMessage("Test", "Test Alternate " + i, "Test");
            msg.put("cntr", Integer.toString(i));
            EventLogger.logEvent(msg);
        }
        fields = new boolean[10];
        for (int i = 0; i < 10; ++i) {
            final Event event = alternate.poll();
            Assert.assertNotNull("Received " + i + " events. Event " + (i + 1) + " is null", event);
            final String value = event.getHeaders().get("cntr");
            Assert.assertNotNull("Missing counter", value);
            final int counter = Integer.parseInt(value);
            if (fields[counter]) {
                Assert.fail("Duplicate event");
            } else {
                fields[counter] = true;
            }
        }
        for (int i = 0; i < 10; ++i) {
            Assert.assertTrue("Channel contained event, but not expected message " + i, fields[i]);
        }
    }

    @Test
    public void testPerformance() throws Exception {
        long start = System.currentTimeMillis();
        int count = 1000;
        for (int i = 0; i < count; ++i) {
            final StructuredDataMessage msg = new StructuredDataMessage("Test", "Test Primary " + i, "Test");
            msg.put("counter", Integer.toString(i));
            EventLogger.logEvent(msg);
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Time to log " + count + " events " + elapsed + "ms");
    }


    private String getBody(final Event event) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final InputStream is = new GZIPInputStream(new ByteArrayInputStream(event.getBody()));
        int n = 0;
        while (-1 != (n = is.read())) {
            baos.write(n);
        }
        return new String(baos.toByteArray());

    }

    private static boolean deleteFiles(final File file) {
        boolean result = true;
        if (file.isDirectory()) {

            final File[] files = file.listFiles();
            for (final File child : files) {
                result &= deleteFiles(child);
            }

        } else if (!file.exists()) {
            return true;
        }

        return result &= file.delete();
    }

    private static class EventCollector implements AvroSourceProtocol {
        private final LinkedBlockingQueue<AvroFlumeEvent> eventQueue = new LinkedBlockingQueue<AvroFlumeEvent>();

        private final NettyServer nettyServer;


        public EventCollector(int port) {
            Responder responder = new SpecificResponder(AvroSourceProtocol.class, this);
            nettyServer = new NettyServer(responder, new InetSocketAddress(HOSTNAME, port));
            nettyServer.start();
        }

        public void stop() {
            nettyServer.close();
        }

        public Event poll() {

            AvroFlumeEvent avroEvent = null;
            try {
                avroEvent = eventQueue.poll(30000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                // Ignore the exception.
            }
            if (avroEvent != null) {
                return EventBuilder.withBody(avroEvent.getBody().array(),
                    toStringMap(avroEvent.getHeaders()));
            } else {
                System.out.println("No Event returned");
            }
            return null;
        }

        public Status append(AvroFlumeEvent event) throws AvroRemoteException {
            eventQueue.add(event);
            return Status.OK;
        }

        public Status appendBatch(List<AvroFlumeEvent> events)
            throws AvroRemoteException {
            Preconditions.checkState(eventQueue.addAll(events));
            return Status.OK;
        }
    }

    private static Map<String, String> toStringMap(Map<CharSequence, CharSequence> charSeqMap) {
        Map<String, String> stringMap = new HashMap<String, String>();
        for (Map.Entry<CharSequence, CharSequence> entry : charSeqMap.entrySet()) {
            stringMap.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return stringMap;
    }

    private static int[] findFreePorts(int count) throws IOException {
        int[] ports = new int[count];
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            for (int i = 0; i < count; ++i) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
        } finally {
            for (int i = 0; i < count; ++i) {
                if (sockets[i] != null) {
                    try {
                        sockets[i].close();
                    } catch (Exception ex) {
                        // Ignore the error.
                    }
                }
            }
        }
        return ports;
    }
}
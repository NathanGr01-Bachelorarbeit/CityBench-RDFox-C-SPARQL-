package org.java.aceis.utils.RDFox;

import com.hp.hpl.jena.rdf.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.oxfordsemantic.jrdfox.Prefixes;
import tech.oxfordsemantic.jrdfox.client.DataStoreConnection;
import tech.oxfordsemantic.jrdfox.client.ServerConnection;
import tech.oxfordsemantic.jrdfox.client.StatisticsInfo;
import tech.oxfordsemantic.jrdfox.exceptions.JRDFoxException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NamedStream implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(NamedStream.class);
    private boolean stop = false;
    public String uri;
    public int windowSizeInMilliSeconds;
    public int stepSizeInMilliSeconds;
    public long systemStartTime;
    public ServerConnection serverConnection;
    private int compactCounter = 0;
    private long i = 0;

    public NamedStream(NamedStream stream) {
        this.stop = stream.stop;
        this.uri = stream.uri;
        this.windowSizeInMilliSeconds = stream.windowSizeInMilliSeconds;
        this.stepSizeInMilliSeconds = stream.stepSizeInMilliSeconds;
        this.systemStartTime = stream.systemStartTime;
        this.serverConnection = stream.serverConnection;
        this.compactCounter = stream.compactCounter;
    }

    public Map<Statement, Long> statementsInWindow = new HashMap<>();

    //public ConcurrentLinkedDeque<RdfQuadruple> rdfQuadruples = new ConcurrentLinkedDeque<>();

    public NamedStream(String uri, int windowSize, int stepSize, ServerConnection serverConnection) {
        this.uri = uri;
        this.windowSizeInMilliSeconds = windowSize;
        this.stepSizeInMilliSeconds = stepSize;
        this.serverConnection = serverConnection;
    }

    @Override
    public void run() {


        while (!stop) {

            i++;
            if (i == 1) {
                systemStartTime = System.currentTimeMillis();
            }
            while (systemStartTime + i * stepSizeInMilliSeconds > System.currentTimeMillis()) {
                waitUntil(systemStartTime + i * stepSizeInMilliSeconds);
            }

            flush(i);
        }
    }

    public void flushIfNecessary() {
        if (i == 0) {
            systemStartTime = System.currentTimeMillis();
            i++;
        }
        if(systemStartTime + i * stepSizeInMilliSeconds < System.currentTimeMillis()) {
            i++;
            flush(i);
        }
    }

    private void flush(long i) {

        //if(!RDFoxWrapper.pause) {
            try (DataStoreConnection dataStoreConnection = serverConnection.newDataStoreConnection(RDFoxWrapper.datastoreName)) {
                Map<Statement, Integer> toBeDeleted = new HashMap();
                Iterator<Statement> iterator = statementsInWindow.keySet().iterator();
                while (iterator.hasNext()) {
                    Statement statement = iterator.next();
                    if(statementsInWindow.get(statement) < systemStartTime + i * stepSizeInMilliSeconds - windowSizeInMilliSeconds) {
                        toBeDeleted.put(statement, 0);
                        iterator.remove();
                    }
                }
                RDFoxWrapper.maintainStreamDatastore(statementsInWindow, toBeDeleted);
                if(compactCounter++ == 100) {
                    dataStoreConnection.compact();
                    compactCounter = 0;
                }
            } catch (JRDFoxException e) {
                e.printStackTrace();
            }
        //}
    }

    public static void waitUntil(long targetTime) {
        long millis = targetTime - System.currentTimeMillis();
        if (millis <= 0)
            return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    public void put(Statement statement) {
        //ReifiedStatement r = statement.createReifiedStatement();
        //r.addLiteral(RDFoxWrapper.timestampProperty, System.currentTimeMillis());
        //reifiedStatementsInWindow.add(r);
        statementsInWindow.put(statement, System.currentTimeMillis());

    }

    public void stop() {
        if (!stop) {
            stop = true;
            logger.info("Stopping namedStream: " + this.uri);
        }
    }

    public int numberOfDifferentEntries(Map<Statement, Long> map1, Map<Statement, Long> map2) {
        int counter = 0;
        for(Statement statement : map1.keySet()) {
            if (!map2.containsKey(statement))
                counter++;
        }
        return counter;
    }
}

package org.java.aceis.utils.RDFox;

import com.hp.hpl.jena.rdf.model.*;
import eu.larkc.csparql.cep.api.RdfQuadruple;
import org.java.aceis.io.streams.rdfox.RDFoxResultObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.oxfordsemantic.jrdfox.Prefixes;
import tech.oxfordsemantic.jrdfox.client.*;
import tech.oxfordsemantic.jrdfox.exceptions.JRDFoxException;
import tech.oxfordsemantic.jrdfox.exceptions.ResourceInUseException;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RDFoxWrapper {
    private static final Logger logger = LoggerFactory.getLogger(RDFoxWrapper.class);

    public static final Property timestampProperty = ModelFactory.createDefaultModel().createProperty("http://example.de#hasTimestamp");

    private static RDFoxWrapper rdfoxWrapper;

    private ServerConnection serverConnection;

    private Map<String, NamedQuery> queries;

    private Set<String> staticData;

    private int queryDuplicates;

    public RDFoxResultObserver rdFoxResultObserver;

    public static String datastoreName = "Datastore";
    private int datastoreCounter = 1;
    public static boolean pause = false;

    private String queryId;
    private NamedQuery firstQuery;

    private static Map<Statement, Integer> statementsToBeDeleted = new ConcurrentHashMap();
    private static Map<Statement, Long> statementsToBeAdded = new ConcurrentHashMap();

    private int counterMethodCalled = 0;
    private int counterUpdateCalled = 0;

    private int counterAddition = 0;
    private int counterDeletion = 0;


    public static RDFoxWrapper getRDFoxWrapper() {
        if (rdfoxWrapper == null) {
            try {
                throw new Exception("RDFoxWrapper not initialised");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return rdfoxWrapper;
    }

    public static RDFoxWrapper getRDFoxWrapper(Map<String, String> queryMap, String rdfoxLicenseKey, int queryInterval, int queryDuplicates) throws JRDFoxException {
        if (rdfoxWrapper == null) {
            rdfoxWrapper = new RDFoxWrapper(queryMap, rdfoxLicenseKey, queryInterval, queryDuplicates);
        }
        return rdfoxWrapper;
    }

    private RDFoxWrapper(Map<String, String> queryMap, String rdfoxLicenseKey, int queryInterval, int queryDuplicates) throws JRDFoxException {
        final String serverURL = "rdfox:local";
        final String roleName = "nathan";
        final String password = "password";

        this.queryDuplicates = queryDuplicates;
        //RDFox Connection
        Map<String, String> parametersServer = new HashMap<String, String>();
        parametersServer.put("license-file", rdfoxLicenseKey);
        logger.debug(Arrays.toString(ConnectionFactory.startLocalServer(parametersServer)));
        ConnectionFactory.createFirstLocalServerRole(roleName, password);
        serverConnection = ConnectionFactory.newServerConnection(serverURL, roleName, password);
        //Datastore
        initializeDatastore();
        this.staticData = new HashSet<>();
        //Load Queries
        this.queries = initializeQueryMap(queryMap, queryInterval);
        firstQuery = queries.values().stream().findAny().get();
        queryId = firstQuery.query;
        queries.clear();
        try {
            loadStaticData();
        } catch (Exception e) {
            logger.error(e.toString());
        }
    }

    private Map<String, NamedQuery> initializeQueryMap(Map<String, String> queryMap, int queryInterval) {
        Map<String, NamedQuery> re = new HashMap<>();
        for (String queryId : queryMap.keySet()) {
            List<NamedStream> namedStreams = new ArrayList<>();
            List<String> staticStreams = new ArrayList<>();
            String query = queryMap.get(queryId);
            String newQuery = "";
            String beginning;
            try (Scanner scanner = new Scanner(query)) {
                beginning = scanner.nextLine();
                while (beginning.startsWith("FROM ")) {
                    if (beginning.startsWith("FROM static")) {
                        staticStreams.add(beginning.split(" ")[2].substring(1, beginning.split(" ")[2].length() - 1));
                        staticData.add(beginning.split(" ")[2].substring(1, beginning.split(" ")[2].length() - 1));
                    }
                    if (beginning.startsWith("FROM dynamic")) {
                        String[] split = beginning.split(" ");
                        namedStreams.add(new NamedStream(split[3].substring(1, split[3].length() - 1), Integer.parseInt(split[5].substring(0, split[5].length() - 2)), Integer.parseInt(split[7].substring(0, split[7].length() - 3)), serverConnection));
                    }
                    beginning = scanner.nextLine();
                }
                while (scanner.hasNextLine()) {
                    newQuery += beginning + "\n";
                    beginning = scanner.nextLine();
                }
            }

            if(queryInterval == 0) {
                queryInterval = namedStreams.stream().mapToInt(c -> c.stepSizeInMilliSeconds).min().orElse(1000);
            }

            newQuery += beginning;
            re.put(queryId, new NamedQuery(newQuery, staticStreams, namedStreams, queryInterval));
        }
        return re;
    }

    public void initializeDatastore() throws JRDFoxException {
        Map<String, String> parametersDatastore = new HashMap<>();
        serverConnection.createDataStore(RDFoxWrapper.datastoreName, "par-simple-nn", parametersDatastore);
    }

    public void loadStaticData() {
        for (String url : staticData) {
            try (DataStoreConnection dataStoreConnection = serverConnection.newDataStoreConnection(RDFoxWrapper.datastoreName)) {
                ImportResult importResult = dataStoreConnection.importData(UpdateType.ADDITION, Prefixes.s_defaultPrefixes, new BufferedInputStream(new URL(url).openStream()));
                logger.info("Static data imported: " + importResult.getNumberOfChangedFacts());
            } catch (JRDFoxException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void putData(String uri, Statement statement) {
        //System.out.println("URI: " + uri);
        for (String s : queries.keySet()) {
            //System.out.println("Query: " + s);
            //queries.get(s).streams.forEach(c -> System.out.println("URI von Stream: " + c.uri));
            queries.get(s).streams.stream().filter(c -> c.uri.equals(uri)).forEach(c -> c.put(statement));
        }
    }

    public void flushIfNecessary(String uri) {
        for (String s : queries.keySet()) {
            queries.get(s).streams.stream().filter(c -> c.uri.equals(uri)).forEach(c -> c.flushIfNecessary());
        }
    }


    public static void maintainStreamDatastore(Queue<ReifiedStatement> currentWindowStatements, List<ReifiedStatement> newTriples, long goOutTime, ServerConnection serverConnection, Prefixes prefixes) throws JRDFoxException {
        //System.out.println("Beginn: " + currentWindowTripels.size());
        currentWindowStatements.addAll(newTriples);
        List<ReifiedStatement> toBeDeleted = new ArrayList<>();
        //System.out.println("Mitte: " + currentWindowTripels.size());
        while (true) {
            if (currentWindowStatements.isEmpty()) {
                break;
            }
            if (currentWindowStatements.peek().getProperty(RDFoxWrapper.timestampProperty).getLiteral().getLong() >= goOutTime) {
                break;
            } else {
                logger.debug(Thread.currentThread().getName() + " Polled" + currentWindowStatements.size());
                toBeDeleted.add(currentWindowStatements.poll());
                logger.debug("" + currentWindowStatements.size());
            }

        }
    }

    public static void maintainStreamDatastore(Map<Statement, Long> currentWindowStatements, Map<Statement, Integer> toBeDeleted) {
        RDFoxWrapper.statementsToBeAdded.putAll(currentWindowStatements);
        statementsToBeDeleted.putAll(toBeDeleted);
    }

    public static void maintainStreamDatastore(Queue<ReifiedStatement> currentWindowStatements, long goOutTime, ServerConnection serverConnection, Prefixes prefixes) throws JRDFoxException {
        maintainStreamDatastore(currentWindowStatements, new ArrayList<>(), goOutTime, serverConnection, prefixes);
    }


    public static String rdfQuadtruplesToTurtleString(Collection<RdfQuadruple> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Model model = ModelFactory.createDefaultModel();
        //Model model = ModelFactory.createDefaultModel();
        for (RdfQuadruple rdfQuadruple : input) {
            Resource resource = model.createResource(rdfQuadruple.getSubject());
            Property property = model.createProperty(rdfQuadruple.getPredicate());
            if (!rdfQuadruple.getObject().contains("^^")) {
                resource.addProperty(property, model.createResource(rdfQuadruple.getObject()));
            } else {
                resource.addProperty(property, rdfQuadruple.getObject());
            }
        }
        OutputStream out = new ByteArrayOutputStream();

        model.write(out, "TURTLE");
        try (FileOutputStream outputStream = new FileOutputStream("out.ttxt", true)) {
            outputStream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //logger.info(out.toString());
        return out.toString();
    }

    public static String reifiedStatementsToTurtleString(Collection<ReifiedStatement> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Model model = ModelFactory.createDefaultModel();
        //Model model = ModelFactory.createDefaultModel();
        for (ReifiedStatement reifiedStatement : input) {
            model.add(reifiedStatement.getStatement());
        }
        String re = "";
        try(OutputStream out = new ByteArrayOutputStream()) {

            model.write(out, "TURTLE");
            try (FileOutputStream outputStream = new FileOutputStream("out.ttxt", true)) {
                outputStream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            //logger.info(out.toString());
            re = out.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return re;
    }

    public static String statementsToTurtleString(Collection<Statement> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Model model = ModelFactory.createDefaultModel();
        //Model model = ModelFactory.createDefaultModel();
        for (Statement statement : input) {
            model.add(statement);
        }
        OutputStream out = new ByteArrayOutputStream();

        model.write(out, "TURTLE");

        return out.toString();
    }

    public void registerQueries(RDFoxResultObserver rdFoxResultObserver, int i) {
        logger.debug("Number of Queries: " + queries.values().size());
        NamedQuery query = new NamedQuery(firstQuery);
        queries.put(queryId + "-" + i, query);

        query.queryAnswerMonitor = rdFoxResultObserver;
        new Thread(query).start();
        for (NamedStream stream : query.streams) {
            System.out.println("-> " + stream);
            startStreamUpdating(stream);
        }
        try {
            Thread.sleep(15);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void startStreamUpdating(NamedStream stream) {
        //new Thread(stream).start();
    }

    public Map<String, NamedQuery> getQueries() {
        return queries;
    }

    public ServerConnection getServerConnection() {
        return serverConnection;
    }

    public void createNewDatastore() {
        try {
            long before = System.currentTimeMillis();
            pause = true;
            try(OutputStream out = new FileOutputStream("tmpDatastore"); DataStoreConnection dataStoreConnection = serverConnection.newDataStoreConnection(RDFoxWrapper.datastoreName)) {
                logger.info("1");
                dataStoreConnection.exportData(Prefixes.s_emptyPrefixes, out, "application/n-triples", new HashMap<>());
                logger.info("2");
                dataStoreConnection.clear();
            }
            boolean flag = true;
            while (flag) {
                try {
                    serverConnection.deleteDataStore(RDFoxWrapper.datastoreName);
                    logger.info("3");
                    flag = false;
                }
                catch (ResourceInUseException e) {

                }
            }

            Map<String, String> parametersDatastore = new HashMap<>();
            serverConnection.createDataStore(RDFoxWrapper.datastoreName, "par-simple-nn", parametersDatastore);
            try(InputStream in = new FileInputStream("tmpDatastore"); DataStoreConnection dataStoreConnection = serverConnection.newDataStoreConnection(RDFoxWrapper.datastoreName)) {
                logger.info("4");
                dataStoreConnection.importData(UpdateType.ADDITION, Prefixes.s_emptyPrefixes, in);
                logger.info("5");
            } catch (Exception e) {
                e.printStackTrace();
            }
            pause = false;
            File file = new File("tmpDatastore");
            file.delete();
            long after = System.currentTimeMillis();
            logger.info("Refreshing took: " + (after - before) + " ms");
        } catch (JRDFoxException | IOException e) {
            e.printStackTrace();
        }
    }

    public void updateDataStoreBatch() {
        counterMethodCalled++;
        if(statementsToBeDeleted.size() != 0 || statementsToBeAdded.size() != 0) {
            counterUpdateCalled++;
            try (DataStoreConnection dataStoreConnection = serverConnection.newDataStoreConnection(RDFoxWrapper.datastoreName)) {
                dataStoreConnection.beginTransaction(TransactionType.READ_WRITE);
                dataStoreConnection.importData(UpdateType.DELETION, Prefixes.s_emptyPrefixes, statementsToTurtleString(statementsToBeDeleted.keySet()));
                dataStoreConnection.importData(UpdateType.ADDITION, Prefixes.s_emptyPrefixes, statementsToTurtleString(statementsToBeAdded.keySet()));
                dataStoreConnection.commitTransaction();
                statementsToBeAdded.clear();
                statementsToBeDeleted.clear();
            } catch (JRDFoxException e) {
                e.printStackTrace();
            }
//            if(counterDeletion < 5) {
//                try (OutputStream outDel = new FileOutputStream("tmpDeletion" + counterDeletion++); OutputStream outAdd = new FileOutputStream("tmpAddition" + counterAddition++);) {
//                    outAdd.write(statementsToTurtleString(statementsToBeAdded.keySet()).getBytes(StandardCharsets.UTF_8));
//                    outDel.write(statementsToTurtleString(statementsToBeDeleted.keySet()).getBytes(StandardCharsets.UTF_8));
//                } catch (FileNotFoundException e) {
//                    e.printStackTrace();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
        }
        /*if(counterMethodCalled % 100 == 1) {
            logger.info("Mathod Called: " + counterMethodCalled + ", " + counterUpdateCalled);
        }*/
    }
}


import java.io.*;
import java.net.*;
import java.util.*;
import java.util.stream.Collectors;

public class Client extends TCPService {

    private List<Record> records;

    private JOBNCmd job;

    private int dataLength;

    public static final int LRR = 0;
    public static final int FF = 1;
    public static final int FC = 2;

    public Client() {
        // connect the ip address and port
        super("127.0.0.1", 50000);
        System.out.println("client version 1.3");
        records = new ArrayList<>();
    }

    public static void main(String[] args) {
        try {
            Client client = new Client();
            client.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        String request = "";
        // HELO and AUTH xxx
        welcome(request);

        // REDY until NONE
        while (!(request = getJob(request)).startsWith("NONE")) {
            // if not a job, skip this loop once
            if (request.startsWith("JOBN")) {
                // get data
                getCapable(request);

                // get computer list and scheduled a job
                scheduleJob(request, getServerList(request), FF);
            }
            // Done a job here
        }

        // QUIT
        close(request);
    }

    public void welcome(String request) {
        sendMessage("HELO");
        readMessage(request);
        sendMessage("AUTH " + System.getProperty("user.name"));
        readMessage(request);
    }

    public String getJob(String request) {
        sendMessage("REDY");
        return readMessage(request);
    }

    public void getCapable(String request) {
        job = new JOBNCmd(request);
        sendMessage("GETS Capable " + job.getJobDetail());
        this.dataLength = Integer.parseInt(readMessage(request).split(" ")[1]);
    }

    public List<ServerType> getServerList(String request) {
        List<ServerType> allServerTypes = new ArrayList<>();
        List<ServerType> serverTypes = new ArrayList<>();
        sendMessage("OK");

        // receive the record of server type
        for (int i = 0; i < dataLength; i++) {
            ServerType st = new ServerType(readMessage(request));
            if (serverTypes.size() > 0 && !serverTypes.get(0).isSameType(st)) {
                allServerTypes.addAll(serverTypes);
                // if did not has the server type record, add into records
                if (records.stream().noneMatch(r -> r.isSameType(serverTypes.get(0).getType()))) {
                    records.add(new Record(serverTypes));
                }
                serverTypes.clear();
            }
            serverTypes.add(st);

        }

        allServerTypes.addAll(serverTypes);
        // if did not has the server type record, add into records
        if (records.stream().noneMatch(r -> r.isSameType(serverTypes.get(0).getType()))) {
            records.add(new Record(serverTypes));
        }

        sendMessage("OK");
        readMessage(request);

        return allServerTypes;
    }

    /**
     * get the next server type id from list
     *
     * @param list the lagest server type list
     * @return server type
     */
    private String getNextServerTypeAndIdFromRecord(List<ServerType> list, int mode) {
        List<Record> newRecords = records.stream()
                .filter(r -> r.isTypeInList(list))
                .collect(Collectors.toList());

        // algorithm design start from FC
        if (mode == FC) {
            return newRecords.stream()
                    .min(Comparator.comparing(Record::getInitialNumOfCores)).get()
                    .getScheduleServer(mode);
        }

        // Stage 2 algorithm
        if (mode == FF) {
            Optional<ServerType> st = list.stream()
                    .filter(serverType -> !(Integer.parseInt(serverType.getRunning()) > 0
                            && Integer.parseInt(serverType.getScheduled()) > 0)
                            && serverType.getFitnessValue(job) >= 0)
                    .min(Comparator.comparing(serverType -> serverType.getFitnessValue(job)));
            list.forEach(l -> System.out.println(l.getType() + " " + l.getSystemId() + " " + l.getFitnessValue(job)));
            if (st.isPresent()) return st.get().getType() + " " + st.get().getSystemId();

            st = list.stream()
                    .filter(serverType -> serverType.getFitnessValue(job) > 0 && serverType.isActive())
                    .min(Comparator.comparing(serverType -> serverType.getFitnessValue(job)));

            if (st.isPresent()) return st.get().getType() + " " + st.get().getSystemId();

            st = list.stream().max(Comparator.comparing(serverType -> serverType.getFitnessValue(job)));
            if (st.isPresent()) return st.get().getType() + " " + st.get().getSystemId();
        }

        // confirm no error
        return newRecords.stream()
                .max(Comparator.comparing(Record::getInitialNumOfCores)).get()
                .getScheduleServer(mode);
    }

    public void scheduleJob(String request, List<ServerType> list, int mode) {
        if (job == null) {
            System.out.println("System Error: Job is null");
            System.exit(1);
        }
        String schedule = getNextServerTypeAndIdFromRecord(list, mode);
        sendMessage("SCHD " + job.getJobId() + " " + schedule);
        while (!(request = readMessage(request)).startsWith("OK")) {
            // Empty
        }
    }

    @Override
    public void close(String request) {
        sendMessage("QUIT");
        super.close(readMessage(request));
        System.exit(1);
    }
}

abstract class TCPService {
    private Socket s;
    private DataOutputStream dos;
    private BufferedReader br;

    private String ipAddress;
    private int port;

    public TCPService(String ip, int port) {
        this.ipAddress = ip;
        this.port = port;

        System.out.println("Connecting to IP : " + ipAddress + ", Port : " + port);
        try {
            this.s = new Socket(ipAddress, port);
            this.dos = new DataOutputStream(s.getOutputStream());
            this.br = new BufferedReader(new InputStreamReader(s.getInputStream()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public abstract void run();

    public String readMessage(String request) {
        try {
            request = br.readLine();
            System.out.println("message from server: " + request);
            return request;
        } catch (Exception e) {
            e.printStackTrace();
            close(request);
        }
        return "Error";
    }

    public void sendMessage(String message) {
        try {
            dos.write((message + "\n").getBytes());
            dos.flush();
            System.out.println("send message to server : " + message);
        } catch (Exception e) {
            e.printStackTrace();
            close(message);
        }
    }

    public void close(String request) {
        try {
            br.close();
            dos.close();
            s.close();
            System.out.println("System closeed, exit program in a few second");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }
}

class ServerType {
    private String type;
    private String systemId;
    private String active;
    private String bootupTime;
    private int cores;
    private String memory;
    private String disk;
    private String scheduled;
    private String running;

    public ServerType(String line) {
        String[] info = line.split(" ");
        int index = 0;
        type = info[index++];
        systemId = info[index++];
        active = info[index++];
        bootupTime = info[index++];
        cores = Integer.parseInt(info[index++]);
        memory = info[index++];
        disk = info[index++];
        scheduled = info[index++];
        running = info[index];
    }

    public String getType() {
        return type;
    }

    public String getSystemId() {
        return systemId;
    }

    public boolean isActive() {
        return active.equals("active") || active.equals("booting");
    }

    public String getBootupTime() {
        return bootupTime;
    }

    public int getCores() {
        return cores;
    }

    public String getMemory() {
        return memory;
    }

    public String getDisk() {
        return disk;
    }

    public String getScheduled() {
        return scheduled;
    }

    public String getRunning() {
        return running;
    }

    public boolean isSameType(ServerType other) {
        return getType().equals(other.getType());
    }

    public boolean isSameType(String other) {
        return getType().equals(other);
    }

    public int getFitnessValue(JOBNCmd job) {
        return cores - Integer.parseInt(job.getCore());
    }
}

class JOBNCmd {
    private String submitTime;
    private String jobId;
    private String estRuntime;
    private String core;
    private String memory;
    private String disk;

    public JOBNCmd(String line) {
        String[] info = line.split(" ");
        int index = 1;
        submitTime = info[index++];
        jobId = info[index++];
        estRuntime = info[index++];
        core = info[index++];
        memory = info[index++];
        disk = info[index];
    }

    public String getSubmitTime() {
        return submitTime;
    }

    public String getJobId() {
        return jobId;
    }

    public String getEstRuntime() {
        return estRuntime;
    }

    public String getCore() {
        return core;
    }

    public String getMemory() {
        return memory;
    }

    public String getDisk() {
        return disk;
    }

    public String getJobDetail() {
        return getCore() + " " + getMemory() + " " + getDisk();
    }
}

class Record {
    private String serverType;
    private int numOfIniCores;
    private int numOfLooping;
    private int numOfServerType;

    public Record(List<ServerType> list) {
        this.serverType = list.get(0).getType();
        this.numOfServerType = list.size();
        this.numOfIniCores = list.get(0).getCores();
        this.numOfLooping = 0;
    }

    public String getServerType() {
        return serverType;
    }

    public int getInitialNumOfCores() {
        return numOfIniCores;
    }

    public int getNumOfLooping() {
        return numOfLooping;
    }

    public int getNumOfServerType() {
        return numOfServerType;
    }

    public boolean isTypeInList(List<ServerType> list) {
        return list.stream().anyMatch(s -> s.getType().equals(getServerType()));
    }

    public boolean isSameType(String compType) {
        return getServerType().equals(compType);
    }

    public int getNextNumOfLooping() {
        return this.numOfLooping = (this.numOfLooping >= this.numOfServerType ? 1 : this.numOfLooping + 1);
    }

    public String getScheduleServer(int mode) {
        if (mode == Client.FC) {
            return getServerType() + " 0";
        }
        // default LRR mode
        else {
            return getServerType() + " " + String.valueOf(getNextNumOfLooping() - 1);
        }
    }
}

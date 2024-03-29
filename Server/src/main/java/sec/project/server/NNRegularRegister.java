package sec.project.server;

import org.javatuples.Quartet;
import org.javatuples.Quintet;
import org.javatuples.Triplet;
import sec.project.library.AsymmetricCrypto;
import sec.project.library.ClientAPI;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

public class NNRegularRegister implements Serializable {

    private Quartet<Integer, String, String, byte[]> valueQuartet;
    private int wts;
    private int rid;
    private GeneralBoard generalBoard;
    private Map<PublicKey, String> ackList;
    private int acks;
    private int nThreads;
    private int byzantineWrite;
    private boolean commit;
    private int commits;
    private int aborts;
    private Map<PublicKey, String> commitList;
    private transient Set<PublicKey> expectedResponses;
    private transient Object lock = new Object();

    public NNRegularRegister(GeneralBoard generalBoard){
        this.generalBoard = generalBoard;
        this.valueQuartet = null;
        this.rid = 0;
        this.wts = 0;
        this.acks = 0;
        this.nThreads = 0;
        this.byzantineWrite = 0;
        this.commit = false;
        this.commits = 0;
        this.aborts = 0;

        this.ackList = new HashMap<>();
        this.commitList = new HashMap<>();
        this.expectedResponses = new HashSet<>();
    }

    public String write(int wts, String value, String clientNumber, byte[] signature, PublicKey clientPublicKey,
                        byte[] senderServerSignature, PublicKey senderServerPublicKey , PrivateKey serverPrivateKey,
                        PublicKey serverPublicKey, Map<PublicKey, ClientAPI> stubs) throws NoSuchPaddingException,
            UnsupportedEncodingException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException,
            Exception {

        if(this.lock == null){
            this.lock = new Object();
        }

        if(this.expectedResponses == null || this.expectedResponses.isEmpty()){
            this.expectedResponses = stubs.keySet();
        }

        if(senderServerPublicKey != null && senderServerSignature != null && !AsymmetricCrypto.validateDigitalSignature(senderServerSignature,
                senderServerPublicKey, value + wts + clientNumber + new String(signature, "UTF-8"))){

            return "Invalid server response";
        }

        if (AsymmetricCrypto.validateDigitalSignature(signature, clientPublicKey,
                value + wts + clientNumber) && wts > this.wts){

            synchronized (this.lock) {
                if (this.valueQuartet == null) {

                    this.valueQuartet = new Quartet<>(wts, value, clientNumber, signature);
                    this.acks++;
                    byte[] sSSignature = AsymmetricCrypto.wrapDigitalSignature(value + wts + clientNumber + new String(signature, "UTF-8"), serverPrivateKey);

                    for (Map.Entry<PublicKey, ClientAPI> entry : stubs.entrySet()) {

                        AsyncSendAck sendAck = new AsyncSendAck(entry.getValue(), clientPublicKey, value, wts, signature,
                                sSSignature, serverPublicKey);
                        new Thread(sendAck).start();

                    }

                    this.nThreads++;

                } else if (Integer.parseInt(this.valueQuartet.getValue2()) > Integer.parseInt(clientNumber)
                        || this.valueQuartet.getValue0() < wts) {

                    this.valueQuartet = new Quartet<>(wts, value, clientNumber, signature);
                    this.ackList = new HashMap<>();
                    this.acks = 1;
                    this.nThreads++;

                    byte[] sSSignature = AsymmetricCrypto.wrapDigitalSignature(value + wts + clientNumber + new String(signature, "UTF-8"), serverPrivateKey);

                    for (Map.Entry<PublicKey, ClientAPI> entry : stubs.entrySet()) {

                        AsyncSendAck sendAck = new AsyncSendAck(entry.getValue(), clientPublicKey, value, wts, signature,
                                sSSignature, serverPublicKey);
                        new Thread(sendAck).start();

                    }
                } else if (this.valueQuartet.getValue0() == wts && !this.valueQuartet.getValue1().equals(value)
                        && Integer.parseInt(this.valueQuartet.getValue2()) == Integer.parseInt(clientNumber)){

                    this.byzantineWrite++;

                    return null;
                } else if (this.valueQuartet.getValue0() == wts
                        && Integer.parseInt(this.valueQuartet.getValue2()) == Integer.parseInt(clientNumber)) {

                    if (stubs.containsKey(senderServerPublicKey) && !ackList.containsKey(senderServerPublicKey)) {
                        this.ackList.put(senderServerPublicKey, "Ack");
                        this.acks++;
                        return null;
                    }

                    return null;
                }
            }

            //Waits until one value has enough acks or if the number of byzantine writes makes impossible to have enough acks
            //Byzantine write is when an client for the same wts sends different values to each server
            while(this.acks <= (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2
                    && this.byzantineWrite < (stubs.size() + 1) - (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2){

                Thread.sleep(250);
            }

            if (this.byzantineWrite >= (stubs.size() + 1) - (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2) {
                synchronized (this.lock) {
                    this.nThreads--;
                    if (this.nThreads == 0) {
                        if(!commit){
                            tryToCommitInGeneralBoard(null, null, serverPublicKey, serverPrivateKey,stubs);
                        }
                        this.ackList = new HashMap<>();
                        this.acks = 0;
                        this.byzantineWrite = 0;
                        this.valueQuartet = null;
                        this.commit = false;
                    }
                    throw new Exception("Client " + clientNumber + " attempted byzantine write");
                }
            } else if (clientNumber.equals(this.valueQuartet.getValue2())) {
                tryToCommitInGeneralBoard(this.valueQuartet, clientPublicKey, serverPublicKey, serverPrivateKey, stubs);
                synchronized (this.lock) {
                    this.commit = true;
                    this.wts = wts;
                    this.nThreads--;
                    if (this.nThreads == 0) {
                        this.ackList = new HashMap<>();
                        this.acks = 0;
                        this.byzantineWrite = 0;
                        this.valueQuartet = null;
                        this.commit = false;
                    }
                }
                return "ACK";
            } else {
                synchronized (this.lock) {
                    this.nThreads--;
                    if (this.nThreads == 0) {
                        if(!commit){
                            tryToCommitInGeneralBoard(null, null, serverPublicKey, serverPrivateKey,stubs);
                        }
                        this.byzantineWrite = 0;
                        this.ackList = new HashMap<>();
                        this.acks = 0;
                        this.valueQuartet = null;
                        this.commit = false;
                    }
                }

                throw new Exception("Write from " + clientNumber + " was unsuccessful");
            }


        }

        //merely representative, the method never returns this.
        return "FAIL";
    }

    public void tryToCommitInGeneralBoard(Quartet<Integer, String, String, byte[]> valueQuartet, PublicKey clientPublicKey,
                                          PublicKey serverPublicKey, PrivateKey serverPrivateKey, Map<PublicKey, ClientAPI> stubs){

        try {

            this.expectedResponses =  stubs.keySet();

            if(valueQuartet == null){

                byte[] sSSignature = AsymmetricCrypto.wrapDigitalSignature("abort", serverPrivateKey);

                for (Map.Entry<PublicKey, ClientAPI> entry : stubs.entrySet()) {

                    AsyncReadyToCommit readyToCommit = new AsyncReadyToCommit(entry.getValue(), clientPublicKey, null,
                            sSSignature, serverPublicKey);
                    new Thread(readyToCommit).start();

                }

                synchronized (this.lock) {
                    this.aborts++;
                }

            } else {

                byte[] sSSignature = AsymmetricCrypto.wrapDigitalSignature(valueQuartet.getValue0() + valueQuartet.getValue1()
                        + valueQuartet.getValue2() + new String(valueQuartet.getValue3(), "UTF-8"), serverPrivateKey);

                for (Map.Entry<PublicKey, ClientAPI> entry : stubs.entrySet()) {

                    AsyncReadyToCommit readyToCommit = new AsyncReadyToCommit(entry.getValue(), clientPublicKey, valueQuartet,
                            sSSignature, serverPublicKey);
                    new Thread(readyToCommit).start();

                }

                synchronized (this.lock) {
                    this.commits++;
                }

            }

            while(this.commits <= (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2
                    && this.aborts <= (stubs.size() + 1) - (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2){}

            if (this.aborts > (stubs.size() + 1) - (stubs.size() + 1 + ((stubs.size() + 1) / 3)) / 2){
                throw new Exception("Unable to commit");
            }else{
                this.generalBoard.addAnnouncement(this.valueQuartet);
                return;
            }

        } catch (Exception e){
            e.printStackTrace();
        }

    }

    public void addCommitRequest(PublicKey clientPublicKey, Quartet<Integer, String, String, byte[]> valueQuartet,
                                 byte[] sSSignature, PublicKey serverPublicKey){

        try {

            if (valueQuartet != null && AsymmetricCrypto.validateDigitalSignature(sSSignature, serverPublicKey, valueQuartet.getValue0() +
                    valueQuartet.getValue1() + valueQuartet.getValue2() + new String(valueQuartet.getValue3(), "UTF-8"))
                    && AsymmetricCrypto.validateDigitalSignature(valueQuartet.getValue3(), clientPublicKey, valueQuartet.getValue1()
                    + valueQuartet.getValue0() + valueQuartet.getValue2()) && this.expectedResponses.contains(serverPublicKey)) {

                if(this.valueQuartet != null && this.valueQuartet.getValue0().intValue() == valueQuartet.getValue0().intValue()
                        && this.valueQuartet.getValue1().equals(valueQuartet.getValue1())
                        && this.valueQuartet.getValue2().equals(valueQuartet.getValue2())
                        && (new String(this.valueQuartet.getValue3(), "UTF-8")).equals(new String(valueQuartet.getValue3(), "UTF-8"))) {

                    synchronized (this.lock) {
                        this.commits++;
                    }
                } else {
                    synchronized (this.lock) {
                        this.aborts++;
                    }
                }

            } else if(AsymmetricCrypto.validateDigitalSignature(sSSignature,serverPublicKey,"abort")
                    && this.expectedResponses.contains(serverPublicKey)){

                if(true){
                    synchronized (this.lock){
                        this.aborts++;
                    }
                }
            }

        } catch (Exception e){
            e.printStackTrace();
        }

    }

    public ArrayList<Quintet<Integer, String, String, byte[], ArrayList<Integer>>> read(int number, int rid, byte[] signature, PublicKey clientPublicKey) throws NoSuchPaddingException,
            UnsupportedEncodingException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {


        if (AsymmetricCrypto.validateDigitalSignature(signature, clientPublicKey,"" + number + rid)){

            return this.generalBoard.getAnnouncementsQuartets(number);
        }

        return null;
    }

    public int getWts() { return this.wts; }

    public int getRid() { return this.rid; }

    public Quartet<Integer, String, String, byte[]> getValueQuartet() { return this.valueQuartet; }

    public GeneralBoard getGeneralBoard() { return this.generalBoard; }
}

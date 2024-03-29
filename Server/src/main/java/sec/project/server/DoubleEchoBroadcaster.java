package sec.project.server;

import org.javatuples.Pair;
import org.javatuples.Triplet;
import sec.project.library.AsymmetricCrypto;
import sec.project.library.ClientAPI;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.rmi.RemoteException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

public class DoubleEchoBroadcaster implements Serializable {

    private PrivateKey serverPrivateKey;
    private PublicKey serverPublicKey;

    private ClientLibrary clientLibrary;
    private boolean sentEcho;
    private boolean sentReady;
    private boolean delivered;
    private Map<PublicKey, Triplet<Integer, String, byte[]>> echoes; /* Overhead the albatross... */
    private Map<Pair<Integer, String>, Integer> echoMessagesCount;
    private Triplet<Integer, String, byte[]> echoedMessage;
    private Map<PublicKey, Triplet<Integer, String, byte[]>> readys;
    private Map<Pair<Integer, String>, Integer> readyMessagesCount;
    private Triplet<Integer, String, byte[]> readyedMessage;
    private Map<PublicKey, ClientAPI> stubs;

    public DoubleEchoBroadcaster(ClientLibrary clientLibrary, Map<PublicKey, ClientAPI> stubs){
        this.clientLibrary = clientLibrary;
        this.stubs = stubs;
    }

    public Triplet<Integer, String, byte[]> write(Triplet <Integer, String, byte[]> valueTriplet, PrivateKey serverPrivateKey, PublicKey serverPublicKey) throws UnsupportedEncodingException, IllegalBlockSizeException, InvalidKeyException, BadPaddingException, NoSuchAlgorithmException, NoSuchPaddingException, RemoteException, InterruptedException {

        //System.out.println("DEBUG: Server received broadcast request. Args: " + valueTriplet.getValue0() + " | " + valueTriplet.getValue1());

        this.serverPrivateKey = serverPrivateKey;
        this.serverPublicKey = serverPublicKey;
        this.echoes = new HashMap<>();
        this.echoMessagesCount = new HashMap<>();
        this.readys = new HashMap<>();
        this.readyMessagesCount = new HashMap<>();
        this.sentEcho = false;
        this.sentReady = false;
        this.delivered = false;

        if(!this.sentEcho){

            //System.out.println("DEBUG: Server initiated sending of ECHO messages.");

            this.sentEcho = true;

            //echo to myself
            echo(this.clientLibrary.getClientPublicKey(), valueTriplet, AsymmetricCrypto.wrapDigitalSignature( this.clientLibrary.getClientPublicKey() + AsymmetricCrypto.transformTripletToString(valueTriplet), this.serverPrivateKey), this.serverPublicKey);

            for (Map.Entry<PublicKey, ClientAPI> stub : this.stubs.entrySet()){
                AsyncSendEcho asyncSendEcho = new AsyncSendEcho(stub.getValue(), this.clientLibrary.getClientPublicKey(), valueTriplet, AsymmetricCrypto.wrapDigitalSignature(
                        this.clientLibrary.getClientPublicKey() + AsymmetricCrypto.transformTripletToString(valueTriplet), this.serverPrivateKey), this.serverPublicKey, false);
                new Thread(asyncSendEcho).start();
            }

        }

        int seconds = 0;
        while(!this.delivered){

            Thread.sleep(100);
            seconds++;
            if (seconds > 100) {

                //System.out.println("TIMEOUT: Could not complete the ADEB algorithm.");

                return null;
            }

        }

        //System.out.println("DEBUG: Server completed READY phase." );
        //System.out.println("DEBUG: Server completed broadcast request.");

        return readyedMessage;

    }

    public synchronized void echo(PublicKey clientPublicKey, Triplet<Integer, String, byte[]> message, byte[] signature, PublicKey serverPublicKey) throws UnsupportedEncodingException, IllegalBlockSizeException, InvalidKeyException, BadPaddingException, NoSuchAlgorithmException, NoSuchPaddingException, RemoteException {

        //System.out.println("DEBUG: Server received ECHO. Args: " + message.getValue0() + " | " + message.getValue1());

        if ((this.stubs.containsKey(serverPublicKey) || serverPublicKey.equals(this.serverPublicKey)) && AsymmetricCrypto.validateDigitalSignature(signature, serverPublicKey,
                clientPublicKey + AsymmetricCrypto.transformTripletToString(message)) && this.echoes.get(serverPublicKey) == null) {


            //System.out.println("DEBUG: Server validated ECHO signature.");

            this.echoes.put(serverPublicKey, message);
            Pair<Integer, String> rawMessage = new Pair<>(message.getValue0(), message.getValue1());

            if(this.echoMessagesCount.get(rawMessage) == null){
                this.echoMessagesCount.put(rawMessage, 1);
            } else {
                int count = this.echoMessagesCount.get(rawMessage);
                this.echoMessagesCount.put(rawMessage, count + 1);
            }

            //System.out.println("DEBUG: State of the server's ECHO message count: " + this.echoMessagesCount);

            if(this.echoMessagesCount.get(rawMessage) > (this.stubs.size() + (this.stubs.size() / 3)) / 2 && this.sentReady == false){

                //System.out.println("DEBUG: Server initiated sending of READY messages.");

                this.sentReady = true;
                this.echoedMessage = message;

                //ready to myself
                ready(this.clientLibrary.getClientPublicKey(), message, AsymmetricCrypto.wrapDigitalSignature(
                        this.clientLibrary.getClientPublicKey() + AsymmetricCrypto.transformTripletToString(message), this.serverPrivateKey), this.serverPublicKey);

                for (Map.Entry<PublicKey, ClientAPI> stub : this.stubs.entrySet()){

                    //System.out.println("DEBUG: Server will send a READY message from ECHO...");

                    AsyncSendEcho asyncSendEcho = new AsyncSendEcho(stub.getValue(), this.clientLibrary.getClientPublicKey(), message, AsymmetricCrypto.wrapDigitalSignature(
                            this.clientLibrary.getClientPublicKey() + AsymmetricCrypto.transformTripletToString(message), this.serverPrivateKey), this.serverPublicKey, true);
                    new Thread(asyncSendEcho).start();

                    //System.out.println("DEBUG: Server sent READY from ECHO. Args: " + message.getValue0() + " | " + message.getValue1());

                }
            }
        }
    }

    public synchronized void ready(PublicKey clientPublicKey, Triplet<Integer, String, byte[]> message, byte[] signature, PublicKey serverPublicKey) throws UnsupportedEncodingException, IllegalBlockSizeException, InvalidKeyException, BadPaddingException, NoSuchAlgorithmException, NoSuchPaddingException, RemoteException {

        //System.out.println("DEBUG: Server received READY. Args: " + message.getValue0() + " | " + message.getValue1());

        if ((this.stubs.containsKey(serverPublicKey) || serverPublicKey.equals(this.serverPublicKey)) && AsymmetricCrypto.validateDigitalSignature(signature, serverPublicKey,
                clientPublicKey + AsymmetricCrypto.transformTripletToString(message)) && this.readys.get(serverPublicKey) == null) {

            //System.out.println("DEBUG: Server validated READY signature.");

            this.readys.put(serverPublicKey, message);
            Pair<Integer, String> rawMessage = new Pair<>(message.getValue0(), message.getValue1());

            if(this.readyMessagesCount.get(rawMessage) == null){
                this.readyMessagesCount.put(rawMessage, 1);
            } else {
                int count = this.readyMessagesCount.get(rawMessage);
                this.readyMessagesCount.put(rawMessage, count + 1);
            }

            //System.out.println("DEBUG: State of the server's READY message count: " + this.readyMessagesCount);

            if(this.readyMessagesCount.get(rawMessage) > 2 * (this.stubs.size() / 3) && this.delivered == false){

                //System.out.println("DEBUG: Server is ready to deliver. " + this.readyMessagesCount);

                this.readyedMessage = message;
                this.delivered = true;
                return;
            }

            if(this.readyMessagesCount.get(rawMessage) > (this.stubs.size() / 3) && this.sentReady == false){

                //System.out.println("DEBUG: Server initiated amplification step");

                this.sentReady = true;
                this.readyedMessage = message;
                for (Map.Entry<PublicKey, ClientAPI> stub : this.stubs.entrySet()){
                    AsyncSendEcho asyncSendEcho = new AsyncSendEcho(stub.getValue(), this.clientLibrary.getClientPublicKey(), message, AsymmetricCrypto.wrapDigitalSignature(
                            this.clientLibrary.getClientPublicKey() + AsymmetricCrypto.transformTripletToString(message), this.serverPrivateKey), this.serverPublicKey, true);
                    new Thread(asyncSendEcho).start();
                }

            }
        }
    }
}

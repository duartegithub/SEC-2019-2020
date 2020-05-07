package sec.project.client;

import sec.project.library.Acknowledge;
import sec.project.library.AsymmetricCrypto;
import sec.project.library.ClientAPI;

import java.security.PublicKey;
import java.util.Map;

public class AsyncLogin implements Runnable {

    private Map.Entry<PublicKey, ClientAPI> stub;
    private Client client;

    public AsyncLogin(Map.Entry<PublicKey, ClientAPI> stub, Client client){
        this.stub = stub;
        this.client = client;
    }

    @Override
    public void run() {

        try{

            Acknowledge response = this.stub.getValue().login(this.client.getClientPublicKey());
            if (AsymmetricCrypto.validateDigitalSignature(response.getSignature(), this.stub.getKey(), response.getMessage())){
                this.client.getLoginResponses().put(stub.getKey(), response.getMessage());
                this.client.incrementNumberOfLoginResponses();
            }

        } catch (Exception e){
            e.printStackTrace();
            System.out.println("Failed retrieving the postWts and postGeneralWts from the servers");
        }

    }
}
package sec.project.client;

import sec.project.library.ClientAPI;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.*;

/**
 * Hello world!
 *
 */
public class ClientInit {

    private static Registry registry;
    private static ClientAPI stub;
    private static Client client;

    public static void main( String[] args ) {

        System.out.println( "\nHello World!" );
        ClientInit clientInit = new ClientInit(7654);

        while(true){
        }

    }

    public ClientInit(int server_port){

        try {

            this.registry = LocateRegistry.getRegistry(server_port);
            this.stub = (ClientAPI) registry.lookup("localhost:" + String.valueOf(server_port) + "/ClientAPI");
            this.client = new Client(stub);
            System.err.println( "\nClient ready." );
            client.execute();

        } catch (Exception e) {

            System.err.println("\nClient exception: " + e.toString());
            e.printStackTrace();

        }
    }
}

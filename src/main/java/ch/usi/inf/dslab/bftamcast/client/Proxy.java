package ch.usi.inf.dslab.bftamcast.client;

import bftsmart.tom.ServiceProxy;
import ch.usi.inf.dslab.bftamcast.RequestIf;

/**
 * @author Paulo Coelho - paulo.coelho@usi.ch
 */
public class Proxy implements ProxyIf {

    private boolean ng, threeLevels;
    private int proxyId;
    private ServiceProxy[] localClients;
    private ServiceProxy[] globalClients;

    public Proxy(int clientId, String globalConfigPaths[], String[] localConfigPaths) {
        proxyId = clientId;
        globalClients = new ServiceProxy[globalConfigPaths.length];

        for (int i = 0; i < globalConfigPaths.length; i++) {
            globalClients[i] = new ServiceProxy(proxyId, globalConfigPaths[i]);
        }

        ng = (localConfigPaths == null);//false
        threeLevels = globalConfigPaths.length > 1;

        if (!ng) {
            localClients = new ServiceProxy[localConfigPaths.length];
            for (int i = 0; i < localConfigPaths.length; i++)
                localClients[i] = new ServiceProxy(clientId, localConfigPaths[i]);
        }

    }

    @Override
    public byte[] reliableMulticast(RequestIf req) {
        byte[] response;

        if (req.getDestination().length > 1 || ng)
            response = globalClients[0].invokeUnordered(req.toBytes());
        else
            response = localClients[req.getDestination()[0]].invokeUnordered(req.toBytes());

        req.fromBytes(response);
        return req.getValue();
    }

    @Override
    public byte[] atomicMulticast(RequestIf req) {
        int[] dest = req.getDestination();
        System.out.println("Destination of the current message is: "+dest+"\n" +
                "Destination length is: "+dest.length+"\n"+
                "is three levels? "+threeLevels);

        if (ng || (dest.length > 1 && !threeLevels) || dest.length > 2){
            return globalClients[0].invokeOrdered(req.toBytes());
        }

        if (dest.length == 1)
            return localClients[dest[0]].invokeOrdered(req.toBytes());

        System.out.println("Dest at index 0="+dest[0]+"\n" +
                "Dest at index 1="+dest[1]+"\n" +
                "Calc dest[0]/2 + 1) == dest[1]/2 + 1 is="+((dest[0]/2 + 1) == dest[1]/2 + 1));
        if((dest[0]/2 + 1) == dest[1]/2 + 1)
            return globalClients[dest[0]/2+1].invokeOrdered(req.toBytes());

        System.out.println("Nao entrou em nenhum IF");
        return globalClients[0].invokeOrdered(req.toBytes());
    }
}

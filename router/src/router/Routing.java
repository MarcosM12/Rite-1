/**
 * Redes Integradas de Telecomunicações I
 * MIEEC 2019/2020
 *
 * routing.java
 *
 * Class with routing data, routing logic and data processing
 *
 * Created on August 30, 19:00
 * @author  Luis Bernardo
 */
package router;

import java.util.*;
import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.event.*;

/**
 * Stores routing information for each area
 */
public class Routing {
    /** Maximum length of the Entry vector length */
    public final static int MAX_ENTRY_VEC_LEN= 30;
    /** Time added to the period to define the TTL field of the ROUTE packets */ 
    public final static int TTL_ADD= 10;

    /** Routing table object */
    public RoutingTable rtab;
    /** Unicast datagram socket used to send packets */
    public final DatagramSocket ds;
    /** A multicast socket is used initialy to broadcast the ROUTE packets! */
    public MulticastDaemon mdaemon;
    
    /** Local address name */
    private final char local_name;
   /** ROUTE packet's transmission period (s) */
    private final int period;
    /** Minimum interval between consecutive ROUTE packets (ms) */
    private final int min_interval;
    /** TTL value used in sent ROUTE packets */
    private final int local_TTL;
    /** Neighbour list */
    private final NeighbourList neig;
    /** Reference to main window with GUI */
    private final Router win;
    /** Reference to graphical routing table object */
    private final JTable tableObj;

    /** List of routers with the ROUTE packets' information received (RouterInfo) */
    public HashMap<Character, RouterInfo> map; 
     /** Time of the last ROUTE packet sent */
    public Date lastSending;
    /** Sequence number of the next ROUTE packet to be sent */
    private int route_seq;
    /** Timer object that sends ROUTE packets */
    private javax.swing.Timer timer_announce;


    /**
     * Create a new instance of a routing object, that encapsulates routing processes
     * @param local_name    local address
     * @param neig          neighbour list
     * @param period        ROUTE timer period
     * @param min_interval  minimum interval between ROUTE packets sent
     * @param multi_addr    multicast IP address
     * @param multi_port    multicast port number
     * @param win           reference to main window object
     * @param ds            unicast datagram socket
     * @param tableObj      reference to routing table graphical object
     */
    public Routing(char local_name, NeighbourList neig, int period, 
            int min_interval, String multi_addr, int multi_port,
            Router win,  DatagramSocket ds, JTable tableObj) {
        this.local_name= local_name;
        
        this.neig= neig;
        this.win= win;
        this.ds= ds;
        this.tableObj= tableObj;
        this.map = new HashMap<>();
        this.lastSending = null;
        this.timer_announce = null;
        this.route_seq = 1;
        this.period = period;
        this.min_interval = min_interval;
        this.local_TTL = period + Routing.TTL_ADD;

        // Initialize everything
        this.mdaemon= new MulticastDaemon(ds, multi_addr, multi_port, win, this);
        this.rtab= null;
        win.Log2("new routing(local='"+local_name+"', period="+period+
            ", min_interval="+min_interval+")");
    }

    /**
     * Start the routing processes and timers
     * @return true is running, false if starting failed
     */
    public boolean start() {
        // Start mdaemon thread
        if (!mdaemon.valid()) {
            return false;
        }
        if (!mdaemon.isAlive()) {
            mdaemon.start();
        }
        update_routing_table();
        start_announce_timer();
        return true;
    }

    /**
     * Return the local name
     * @return local address string
     */
    public char local_name() {
        return local_name;
    } 

    /** Stops Routing thread */
    public void stop() {
        // Stop multicast daemon
        mdaemon.stopRunning();
        mdaemon= null;
      
        // Stop timer
        stop_announce_timer();        
        // Clean the ROUTE list information
        map.clear();
        
        // Clean routing table
        if(rtab != null) { 
            rtab.clear();
            // Clear routing table window
            update_routing_window(rtab);
            rtab= null;
        }
    }

    /**
     * Prepare a ROUTE packet with the neighbour information 
     * @param name  local name (address)
     * @param seq   sequence number
     * @param TTL   TTL value to put in the packet
     * @param vec   neighbour Entry vector
     * @return the ROUTE packet, or null if error
     */
    public DatagramPacket make_ROUTE_packet(char name, int seq, 
            int TTL, Entry[] vec) {        
        if (vec == null) {
            win.Log("ERROR: null vec in send_ROUTE_packet\n");
            return null;
        }
        win.Log2("make_ROUTE_packet("+name+seq+","+TTL+","+"[");
        for (int i=0;i<vec.length;i++) {
            win.Log2(""+(i>0?",":"")+vec[i].toString());
        }
        win.Log2("])\n");
        
        ByteArrayOutputStream os= new ByteArrayOutputStream();
        DataOutputStream dos= new DataOutputStream(os);
        try {
            dos.writeByte(Router.PKT_ROUTE);
            dos.writeChar(name);
            dos.writeShort(TTL);
            dos.writeInt(seq);
            dos.writeShort(vec.length);
            for (Entry vec1 : vec) {
                vec1.writeEntry(dos);
            }
            byte [] buffer = os.toByteArray();
            DatagramPacket dp= new DatagramPacket(buffer, buffer.length);
            
            return dp;
        }
        catch (IOException e) {
            win.Log("Error making ROUTE: "+e+"\n");                    
            return null;
        }
    }

    /**
     * Return the local Entry vector for area 'area', used to prepare the ROUTE packet
     * @return the Entry vector, or null if error
     */
    public Entry[] local_vec() {
        // Get local vec
        Entry[] lvec;
        lvec = neig.local_vec(false);
        if (lvec == null) { 
            // No local information ??
            win.Log("Internal error in routing.local_vec\n");
            return null;
        }           
        return lvec;
    }

    
    /** Unmarshalls unicast ROUTE packet e process it */
    /**
     * Unmarshall a ROUTE packet and process it
     * @param sender    the sender address
     * @param dp        datagram packet
     * @param ip        IP address of the sender
     * @param dis       input stream object
     * @param mcast     received from multicast socket
     * @return true if packet was handled successfully, false if error
     */
    public boolean process_ROUTE(char sender, DatagramPacket dp, 
            String ip, DataInputStream dis, boolean mcast) {
        
        if (sender == local_name) {
            win.Log2("Packet loopback in process_ROUTE - ignored\n");
            return true;
        }
        try {
            win.Log("PKT_ROUTE("+sender+",");
            String aux;
            int TTL= dis.readShort();
            int seq= dis.readInt();
            aux= "seq="+seq+","+"TTL="+TTL+",";
            int n= dis.readShort();
            aux+= "List:"+n+": ";
            if ((n<=0) || (n>MAX_ENTRY_VEC_LEN)) {
                win.Log("\nInvalid list length '"+n+"'\n");
                return false;
            }
            Entry [] data= new Entry [n];
            for (int i= 0; i<n; i++) {
                try {
                    data[i]= new Entry(dis);
                } catch(IOException e) {
                    win.Log("\nERROR - Invalid vector Entry: "+e.getMessage()+"\n");
                    return false;                    
                }
                aux+= (i==0 ? "" : " ; ") + data[i].toString();
            }
            win.Log(aux+")\n");
            
            // The contents of the received ROUTE packet are stored in
            //      sender, seq, TTL, data
            // The packet was received from ip,dp.getPort()
       
            // For multicast flooding :
            //  You should locate the corresponding RouterInfo object in map
            //      If no one exists, you should create a new one and place it in map
            //      If one exists, you should test seq and the object validity, before updating it
            
            RouterInfo router_packet = new RouterInfo(win, sender,seq,TTL,data);
            if(map.containsKey(sender)){
                
                RouterInfo vt = map.get(sender);
                if( seq > vt.seq)  {
                    map.replace(sender, router_packet);
                   
                }    
                else 
                    return false;
            }
            else 
                map.put(sender, router_packet);
               
            // For unicast flooding:
            //  You do the multicast actions
            //  You also need to get the sending neigbour and reflood the packet to all other neighbors
            //      decrementing TTL
            // ...
           /* if(mcast == false){ //Unicast flooding
                
            
            
            }*/
            // When "Send If Changes" is on:
            //  You need to compare the vector with the previously received one (if valid),
            //      and call network_changed if they differ

            return true;    // If everything was done well
        } catch (IOException e) {
            win.Log("\nERROR - Packet too short\n");
            return false;
        }
    }

    /**
     * Handle multicast ROUTE packets
     *
     * @param sender sender address
     * @param dp datagram packet received
     * @param ip IP address
     * @param dis input stream
     * @return true if handled successfully, false otherwise
     */
    public boolean process_multicast_ROUTE(char sender, DatagramPacket dp,
            String ip, DataInputStream dis) {
        if (sender == local_name) {
            // Packet loopback - ignore
            return true;
        }
        win.Log2("multicast ");
        return process_ROUTE(sender, dp, ip, dis, true);
    }

    /**
     * Get the routing table contents
     *
     * @return the routing table
     */
    public RoutingTable get_routing_table() {
        return rtab;
    }
    
    /*
     *  Sees if node node exists in the local vector of origin node
     * 
     * 
    */
    public boolean NodeExists(Entry node){
        
        boolean ok = false;
        
        for(Entry vector1 : neig.local_vec(true)){
               
              if(node.dest == vector1.dest) ok = true;

        }
        
        return ok;
    }
  

    public RouteEntry SmallestRoute(RoutingTable tab,char origin, int Prevdist){
        
        RouteEntry route1, route = null;
        char nextN;
        int min = 100000000;
        int cont=0;
 
        RouterInfo neighbours = map.get(origin);
        if(neighbours != null){
            for(Entry vector1 : neighbours.vec){
        
                cont = vector1.dist + Prevdist;         
                route1 = tab.get_RouteEntry(vector1.dest);
                if(route1 != null ){
                    if(cont < min && route1.is_final() == false && cont <= Router.MAX_DISTANCE){
                        min = cont;
                        nextN = vector1.dest;
                        route = new RouteEntry(nextN,origin,min);

                     }
                }
                else{
                     if(cont < min && cont <= Router.MAX_DISTANCE){
                        min = cont;
                        nextN = vector1.dest;
                        route = new RouteEntry(nextN,origin,min);

                     }
                }
            }
           
            return route; 
        }
        else 
            return null;
    }
    
   

    
    
    /*******************************
     * Dijkstra implementation
     */

    /**
     * Run the Dijkstra algorithm, setting the routing table in main_rtab
     * variable
     *
     * @param origin name of the starting router
     * @return the routing table calculated
     */
    public RoutingTable run_dijkstra(char origin) {
        char nextN = origin;
        RouteEntry re;
        RoutingTable tab= new RoutingTable();
        int Prevdist = 0;
        boolean tentatives = true;
     
        int min = 1000000, total = 0;
       
        // Create route entry with local node
        re= new RouteEntry(origin, ' ', 0);
        re.set_final();     // Set the local node final
        tab.add_route(re); // Add the route entry to the routing table
        
        // Implement the Dijkstra algorithm here
        // Read carefully the pages 366-369 of Computer Networks 5th edition
        // to understand how it is done
        // Remember that there is a maximum distance allowed!
        // ...
  
        
        for(Entry vector : neig.local_vec(true)){
                if(tab.get_RouteEntry(vector.dest)==null){
                   
                    re = new RouteEntry(vector.dest,vector.dest,vector.dist);
                    tab.add_route(re);
                
                }
                total = vector.dist + Prevdist;         
                if(total != 0){
                    if(total < min && total <= Router.MAX_DISTANCE){
                        min = total;
                        nextN = vector.dest;
                    }
                }
            
        }
     
        if(total!=0){
            //re = new RouteEntry(nextN,origin,min);
            re = tab.get_RouteEntry(nextN);
            re.set_final();
            //tab.add_route(re);
            origin = nextN;
            Prevdist = min;
        }
        else{
            
            return tab;
        
        } 
        
        
        
        
        while(tentatives == true){   

             /*  for(Entry en1 : tab.get_Entry_vector()){

                    if(!NodeExists(en1)) tab.removeRoute(en1.dest);

                }*/
              
                re = SmallestRoute(tab,origin,Prevdist);
               
                if(re != null){ 
                     
                   if(tab.get_RouteEntry(re.dest)==null){
                        re.set_final(); 
                        tab.add_route(re);
                        origin = re.dest;
                        Prevdist = re.dist;
                    }
                    else{
                       
                        RouteEntry re2 = tab.get_RouteEntry(re.dest);
                        if(re2.is_final() == false){
                            re2.update_dist(re.dist);
                            re2.next_hop = re.next_hop;
                            re2.set_final(); 
                            origin = re.dest; 
                            Prevdist = re.dist;
                        }    
                   }
                } 
                else{
                   return tab;
                }

               
               tentatives = false;
               RouterInfo ro = map.get(origin);
               if(ro!=null){
                    for(Entry vector : ro.vec){
                           RouteEntry rou ;
                           rou = tab.get_RouteEntry(vector.dest);
                           if(rou == null){ 
                               tentatives = true;
                               break;

                            }
                           else{
                                if(rou.is_final() == false){ 
                                    tentatives = true;
                                    break;
                                
                                }
                           }
                     }
               }
               
        }
        
        
        return tab; // Return the table   
           
      
                

        
        // Max_Distance=30
        
        
        
    }

    /*******************************
     * ROUTE flooding implementation
     */

    /**
     * Send a ROUTE packet with neighbours' information
     *
     * @param use_multicast if use broadcast to send to neighbors or flooding
     * @return true if successful, false otherwise
     */
    public boolean send_local_ROUTE(boolean use_multicast) {
        if (neig.is_empty()) {
            win.Log("send_local_ROUTE() skipped - empty neighbour list\n");
            return false;
        }

        win.Log("send_local_ROUTE(multicast only)\n");

        //
        Entry[] vec = local_vec();
        if (vec == null) { // No vector
            return false;
        }
        
        DatagramPacket dp = make_ROUTE_packet(win.local_name(), route_seq++, local_TTL, vec);

        try {
            // WARNING: always use multicast
            // Place here the code to send unicast if (!use_multicast)
           /* if(!use_multicast){
               ds.send(dp);
                
               return true; 
            }
            else{*/
                mdaemon.send_packet(dp);

                lastSending = new Date();
                win.ROUTE_snt++;
                win.ROUTE_loc++;
                return true;
            //}    
        } catch (IOException e) {
            win.Log("Error sending ROUTE: " + e + "\n");
            return false;
        }
    }

    /**
     * Update routing table and send ROUTE
     */
    public void update_routing_table() {
        send_local_ROUTE(win.BcastROUTE_selected());

        win.Dijkstra_cnt++;
        rtab= run_dijkstra(win.local_name());
        update_routing_window(rtab);
    }

    /**
     * Update and display the routing table in the GUI
     * @param _rtab  new routing table; if null does not update
     */
    public void update_routing_window(RoutingTable _rtab) {
        win.Log2("update_routing_window\n");
        if (_rtab != null) {
            // Update the main routing table
            this.rtab= _rtab;
        }
            
        Iterator<RouteEntry> iter= null;
        if (rtab!=null) {
            iter= rtab.iterator();
        }

        // update window
        for (int i= 0; i<tableObj.getRowCount(); i++) {
            if ((iter != null) && iter.hasNext()) {
                RouteEntry next= iter.next();
                tableObj.setValueAt(""+next.dest,i,0);
                tableObj.setValueAt(""+next.next_hop,i,1);
                tableObj.setValueAt(""+next.dist,i,2);
            } else {
                tableObj.setValueAt("",i,0);
                tableObj.setValueAt("",i,1);
                tableObj.setValueAt("",i,2);
            }
        }
    }
        

    /* ------------------------------------ */
    // Announce timer
    /**
     * Run the timer responsible for sending periodic ROUTE packets to routers
     *
     * @param initial_delay initial delay until run the first time in (ms)
     */
    

    private void run_announce_timer(int initial_delay) {
       // win.Log("routing.run_announce_timer not implemented yet\n");
       
        ActionListener action = new ActionListener() {  
               
            @Override
            public void actionPerformed(ActionEvent evt) {
               // Code to be run when timer expires
                update_routing_table();
                
            }
            
        };
        timer_announce = new javax.swing.Timer(period * 1000,action);
       // timer_announce.setInitialDelay(initial_delay);
        timer_announce.start();  
       
    

        // Place here the code to create the timer_announce object and define the
        //    timeout event handler
        // It should wait initial_delay ms until triggering the first time;
        //   then on, it should run periodically with period 'period'
        
    };

    
    /**
     * Launches timer responsible for sending periodic distance packets to
     * neighbours
     */
    private void start_announce_timer() {
        // When starting, the first interval is equal to the period
        run_announce_timer(period * 1000);
    }

    /**
     * Stops the timer responsible for sending periodic distance packets to
     * neighbours
     */
    private void stop_announce_timer() {
        if (timer_announce != null) {
            timer_announce.stop();
            timer_announce = null;
        }
    }

    /**
     * Restarts the timer responsible for sending periodic distance packets to
     * neighbours
     */
    private void reset_announce_timer() {
        if ((timer_announce != null) && timer_announce.isRunning()) {
            stop_announce_timer();
        }
        start_announce_timer();
    }

    /**
     * Tests if the minimum interval time has elapsed since last sending
     *
     * @return true if the time elapsed, false otherwise
     */
    public boolean test_time_since_last_update() {
        return (lastSending == null)
                || ((System.currentTimeMillis() - lastSending.getTime()) >= min_interval);
    }

    /**
     * Reschedules the timer to trigger exactly after a min_interval time since
     * last sending
     */
    public void reschedule_announce_timer() {
        win.Log("routing.reschedule_announce_timer not implemented yet\n");
        // use run_announce_timer to wait until min_interval ms since last ROUTE before triggering the timer
        //    lastSending stores the time of the last sending of ROUTE
    }


    /**
     * Handle a network change notification from the neighbour management
     *
     * @param local_neig_change true if a connection to a neighbor changed
     */
    public void network_changed(boolean local_neig_change) {
        if (win.SendIfChanges_selected()) {
            win.Log("network_changed("+(local_neig_change?"local":"remote")+") called\n");

            // COMPLETE THIS PART
            // Recalculate the table and send it if send_always or if the table changed
            // Control the time between calculations and ROUTEs: 
            //     if min_interval was not reached, sleep until that time before sending the ROUTE packet
            // ...
        }
    }

    


    

    /***************************************************************************
     *              DATA HANDLING
     **************************************************************************/

    /**
     * returns next hop to reach destination
     * @param dest destination address
     * @return the address of the next hop, or ' ' if not found.
     */
    public char next_Hop(char dest) {
        if (rtab == null) {
            return ' ';
        }
        return rtab.nextHop(dest);
    }

    /**
     * send a DATA packet using the routing table and the neighbor information
     * @param dest destination address
     * @param dp   datagram packet object
     */
    public void send_data_packet(char dest, DatagramPacket dp) {
        if (win.is_local_name(dest) || win.is_local_group(dest)) {
            // Send to local node
            try {
                dp.setAddress(InetAddress.getLocalHost());
                dp.setPort(ds.getLocalPort());
                ds.send(dp);
                win.DATA_snt++;
            }
            catch (UnknownHostException e) {
                win.Log("Error sending packet to himself: "+e+"\n");
            }
            catch (IOException e) {
                win.Log("Error sending packet to himself: "+e+"\n");
            }
            
        } else { // Send to neighbour router
            char prox= next_Hop(dest);
            if (prox == ' ') {
                win.Log("No route to destination: packet discarded\n");
            } else {
                // Lookup neighbour
                Neighbour pt= neig.locate_neig(prox);
                if (pt == null) {
                    win.Log("Invalid neighbour ("+prox+
                        ") in routing table: packet discarder\n");
                    return;
                }
                try {
                    pt.send_packet(ds, dp);
                    win.DATA_snt++;
                }
                catch(IOException e) {
                    win.Log("Error sending DATA packet: "+e+"\n");
                }
            }            
        }
    }

    /**
     * prepares a data packet; adds local_name to path
     *
     * @param sender sender name
     * @param dest destination name
     * @param seq sequence number
     * @param msg message contents
     * @param path path already transverse
     * @return datagram packet to send
     */
    public DatagramPacket make_data_packet(char sender, int seq, char dest, 
            String msg, String path) {
        ByteArrayOutputStream os= new ByteArrayOutputStream();
        DataOutputStream dos= new DataOutputStream(os);
        try {
            dos.writeByte(Router.PKT_DATA);
            dos.writeChar(sender);
            dos.writeInt(seq);
            dos.writeChar(dest);
            dos.writeShort(msg.length());
            dos.writeBytes(msg);
            dos.writeByte(path.length()+1);
            dos.writeBytes(path+win.local_name());
        }
        catch (IOException e) {
            win.Log("Error encoding data packet: "+e+"\n");
            return null;
        }
        byte [] buffer = os.toByteArray();
        return new DatagramPacket(buffer, buffer.length);
    }
    
    /**
     * prepares a data packet; adds local_name to path and send the packet
     *
     * @param sender sender name
     * @param dest destination name
     * @param seq sequence number
     * @param msg message contents
     * @param path path already transverse
     */
    public void send_data_packet(char sender, int seq, char dest, String msg,
            String path) {
        if (!Character.isUpperCase(sender)) {
            win.Log("Invalid sender '"+sender+"'\n");
            return;
        }
        if (!Character.isUpperCase(dest)) {
            win.Log("Invalid destination '"+dest+"'\n");
            return;
        }
        DatagramPacket dp= make_data_packet(sender, seq, dest, msg, path);
        if (dp != null) {
            send_data_packet(dest, dp);
        }
    }

    /**
     * unmarshals DATA packet e process it
     *
     * @param sender the sender of the packet
     * @param dp datagram packet received
     * @param ip IP of the sender
     * @param dis data input stream
     * @return true if decoding was successful
     */
    public boolean process_DATA(char sender, DatagramPacket dp, 
            String ip, DataInputStream dis) {
        try {
            win.Log("PKT_DATA");
            if (!Character.isUpperCase(sender)) {
                win.Log("Invalid sender '"+sender+"'\n");
                return false;
            }
            // Read seq
            int seq= dis.readInt();
            // Read Dest
            char dest= dis.readChar();
            // Read message
            int len_msg= dis.readShort();
            if (len_msg>255) {
                win.Log(": message too long ("+len_msg+">255)\n");
                return false;
            }
            byte [] sbuf1= new byte [len_msg];
            int n= dis.read(sbuf1,0,len_msg);
            if (n != len_msg) {
                win.Log(": Invalid message length\n");
                return false;
            }
            String msg= new String(sbuf1,0,n);
            // Read path
            int len_path= dis.readByte();
            if (len_path>Router.MAX_PATH_LEN) {
                win.Log(": path length too long ("+len_msg+">"+Router.MAX_PATH_LEN+
                    ")\n");
                return false;
            }
            byte [] sbuf2= new byte [len_path];
            n= dis.read(sbuf2,0,len_path);
            if (n != len_path) {
                win.Log(": Invalid path length\n");
                return false;
            }
            String path= new String(sbuf2,0,n);
            win.Log(" ("+sender+"-"+dest+"-"+seq+"):'"+msg+"':Path='"+path+win.local_name()+
                    (win.is_local_group(dest)?"("+dest+")":"")+"'\n");
            // Test routing table
            if (win.is_local_name(dest) || win.is_local_group(dest) /*Anycast*/) {
                // Arrived at destination
                win.Log("DATA packet reached destination\n");
                return true;
            } else {
                char prox= next_Hop(dest);
                if (prox == ' ') {
                    win.Log("No route to destination: packet discarded\n");
                    return false;
                } else {
                    // Send packet to next hop
                    send_data_packet(sender, seq, dest, msg, path);
                    return true;
                }
            }
        }
        catch (IOException e) {
            win.Log(" Error decoding data packet: " + e + "\n");
        }
        return false;       
    }
    
}

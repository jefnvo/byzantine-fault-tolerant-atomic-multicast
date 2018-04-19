
package ch.usi.inf.dslab.bftamcast.server;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.MessageContext;
import bftsmart.tom.ReplicaContext;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.server.BatchExecutable;
import bftsmart.tom.server.FIFOExecutable;
import bftsmart.tom.server.Replier;
import ch.usi.inf.dslab.bftamcast.graph.Tree;
import ch.usi.inf.dslab.bftamcast.graph.Vertex;
import ch.usi.inf.dslab.bftamcast.kvs.Request;
import ch.usi.inf.dslab.bftamcast.util.RequestTracker;
import io.netty.util.internal.ConcurrentSet;

/**
 * @author Christian Vuerich - christian.vuerich@usi.ch
 *
 */
//TODO since BatchExecutable calls the same method as FIFOExecutable in performance try to remove BatchExecutable to check perf.
//public class ReplicaReplier implements Replier, FIFOExecutable, BatchExecutable, Serializable, ReplyListener {
	public class ReplicaReplier implements Replier, FIFOExecutable, Serializable, ReplyListener {

	private static final long serialVersionUID = 1L;
	// keep the proxy of all groups and compute lca etc/
	private Tree overlayTree;
	private int groupId, maxOutstanding;
	protected transient Lock replyLock;
	protected transient Condition contextSet;
	protected transient ReplicaContext rc;
	protected Request req;

	// key store map
	private Map<Integer, byte[]> table;
	// trackers for replies from replicas
	private ConcurrentMap<Integer, ConcurrentHashMap<Integer, RequestTracker>> repliesTracker;
	// map for finished requests replies
	private ConcurrentMap<Integer, ConcurrentHashMap<Integer, Request>> processedReplies;
	// map for not processed requests
	private ConcurrentMap<Integer, ConcurrentHashMap<Integer, ConcurrentSet<TOMMessage>>> globalReplies;
	// vertex in the overlay tree representing my group
	private Vertex me;

	/**
	 * Constructor
	 * 
	 * @param RepID
	 * @param groupID
	 * @param treeConfig
	 */
	public ReplicaReplier(int RepID, int groupID, String treeConfig, int maxOutstanding) {

		this.overlayTree = new Tree(treeConfig, UUID.randomUUID().hashCode());
		this.groupId = groupID;
		this.maxOutstanding = maxOutstanding;
		me = overlayTree.findVertexById(groupID);
		replyLock = new ReentrantLock();
		contextSet = replyLock.newCondition();
		globalReplies = new ConcurrentHashMap<>();
		repliesTracker = new ConcurrentHashMap<>();
		processedReplies = new ConcurrentHashMap<>();

		table = new TreeMap<>();
	}

	@Override
	public void manageReply(TOMMessage request, MessageContext msgCtx) {
		//TODO limit outstanding 
		req = new Request(request.getContent());

		while (rc == null) {
			try {
				this.replyLock.lock();
				this.contextSet.await();
				this.replyLock.unlock();
			} catch (InterruptedException ex) {
				Logger.getLogger(ReplicaReplier.class.getName()).log(Level.SEVERE, null, ex);
			}
		}

		// extract request from tom message
		req = new Request(request.getContent());
		req.setSender(groupId);

		// already processes and answered request to other replicas, send what has
		// been done
		if (processedReplies.get(req.getClient()) != null
				&& processedReplies.get(req.getClient()).containsKey(req.getSeqNumber())) {
			request.reply.setContent(processedReplies.get(req.getClient()).get(req.getSeqNumber()).toBytes());
			rc.getServerCommunicationSystem().send(new int[] { request.getSender() }, request.reply);
		}
		// client contacted server directly, no majority needed
		else if (req.getDestination().length == 1) {
			execute(req);

			request.reply.setContent(req.toBytes());
			rc.getServerCommunicationSystem().send(new int[] { request.getSender() }, request.reply);
			// create entry for client replies if not already these
			processedReplies.computeIfAbsent(req.getClient(), k -> new ConcurrentHashMap<>());
			// add processed reply to client replies
			processedReplies.get(req.getClient()).put(req.getSeqNumber(), req);
		}
		// another group contacted me, majority needed
		else {
			// majority of parent group replicas f+1
			int majReplicasOfSender = 0;
			// this group is not the lcs, so not contacted directly from client
			if (groupId != req.getLcaID()) {
				majReplicasOfSender = me.getParent().getProxy().getViewManager().getCurrentViewF() + 1;
			}

			// save message
			ConcurrentSet<TOMMessage> msgs = saveRequest(request, req.getSeqNumber(), req.getClient());
			// check if majority of parent contacted me, and request is the same
			// -1 because the request used to compare other is already in msgs
			

			// majority of replicas sent request and this replica is not already
			// processing
			// the request (not processing it more than once)
			//count TODO
			if (msgs.size() >= majReplicasOfSender && (repliesTracker.get(req.getClient()) == null
					|| !repliesTracker.get(req.getClient()).containsKey(req.getSeqNumber()))) {

				req = getMajreq(msgs, majReplicasOfSender);
				req.setSender(groupId);
//				System.out.println("asdflkhjadsfdka    " + req);
				int[] destinations = req.getDestination();
				int[] destinationsH = req.getDestinationHandled();
				boolean addreq = false;
				Map<Vertex, Integer> toSend = new HashMap<>();
				// List<Vertex>
				for (int i = 0; i < destinations.length; i++) {
					if(destinationsH[i] == -1) {
					// I am a target, compute but wait for majority of other destination to
					// execute
					// the same to asnwer
					if (destinations[i] == groupId) {
						destinationsH[i] = destinations[i];
						execute(req);
						// System.out.println(req.getValue());
						addreq = true;
					}
					// my child in tree is a destination, forward it
					else if (me.getChildernIDs().contains(destinations[i])) {
						Vertex v = me.getChild(destinations[i]);
						destinationsH[i] = destinations[i];
						toSend.put(v, v.getProxy().getViewManager().getCurrentViewF() + 1);
					}
					// destination must be in the path of only one of my childrens
					else {

						for (Vertex v : me.getChildren()) {
							if (v.inReach(destinations[i])) {
								if (!toSend.keySet().contains(v)) {
									destinationsH[i] = destinations[i];
									toSend.put(v, v.getProxy().getViewManager().getCurrentViewF() + 1);
								}
								break;// only one path
							}
						}
					}
					}

				}
				req.setDestinationHandled(destinationsH);

				// no other destination is in my reach, send reply back
				if (toSend.keySet().isEmpty()) {
					// create entry for client replies if not already these
					processedReplies.computeIfAbsent(req.getClient(), k -> new ConcurrentHashMap<>());
					// add processed reply to client replies
					processedReplies.get(req.getClient()).put(req.getSeqNumber(), req);
					for (TOMMessage msg : msgs) {
						msg.reply.setContent(req.toBytes());
						rc.getServerCommunicationSystem().send(new int[] { msg.getSender() }, msg.reply);
					}
					// can remove, later requests will receive answers directly from already
					// processes replies
					globalReplies.get(req.getClient()).remove(req.getSeqNumber());
					return;
				} else {

					// else, tracker for received replies and majority needed
					// add map for a client tracker if absent
					repliesTracker.computeIfAbsent(req.getClient(), k -> new ConcurrentHashMap<>());

					if (addreq) {
						repliesTracker.get(req.getClient()).put(req.getSeqNumber(), new RequestTracker(toSend, req));
					} else {
						repliesTracker.get(req.getClient()).put(req.getSeqNumber(), new RequestTracker(toSend, null));
					}
				}

				for (Vertex v : toSend.keySet()) {
					v.getProxy().invokeAsynchRequest(request.getContent(), this, TOMMessageType.ORDERED_REQUEST);
				}
			}
		}

	}

	/**
	 * save TomMessage for each client and seq#, to track how many requests have
	 * been received (target f+1 identical)
	 * 
	 * @param request
	 * @param seqNumber
	 * @param clientID
	 * @return the vector of received request for a given client and sequence
	 *         number, used to check f+1
	 */
	protected ConcurrentSet<TOMMessage> saveRequest(TOMMessage request, int seqNumber, int clientID) {
		Map<Integer, ConcurrentSet<TOMMessage>> map = globalReplies.computeIfAbsent(clientID,
				k -> new ConcurrentHashMap<>());
		ConcurrentSet<TOMMessage> messages = map.computeIfAbsent(seqNumber, k -> new ConcurrentSet<>());
		if (request != null) {
			messages.add(request);
		}
		return messages;
	}

	/**
	 * execute the request give as parameter and put the resulting byte[] in the
	 * request result[groupid][]
	 * 
	 * @param req
	 */
	protected void execute(Request req) {
		byte[] resultBytes;
		switch (req.getType()) {
		case PUT:
			resultBytes = table.put(req.getKey(), req.getValue());
			break;
		case GET:
			resultBytes = table.get(req.getKey());
			break;
		case REMOVE:
			resultBytes = table.remove(req.getKey());
			break;
		case SIZE:
			resultBytes = String.valueOf(table.size()).getBytes();
			break;
		default:
			resultBytes = null;
			System.err.println("Unknown request type: " + req.getType());
		}

		// set result for this group
		req.setResult(resultBytes, groupId);
	}

	/**
	 * extract the replica context
	 */
	@Override
	public void setReplicaContext(ReplicaContext rc) {
		this.replyLock.lock();
		this.rc = rc;
		this.contextSet.signalAll();
		this.replyLock.unlock();
	}

	/**
	 * Async reply reciever
	 */
	@Override
	public void replyReceived(RequestContext context, TOMMessage reply) {
		System.out.println("recieved");
		// unpack request from reply
		Request replyReq = new Request(reply.getContent());
		// get the tracker for that request
		RequestTracker tracker = repliesTracker.get(replyReq.getClient()).get(replyReq.getSeqNumber());
		// add the reply to tracker and if all involved groups reached their f+1 quota
		if (tracker != null && tracker.addReply(replyReq)) {
			// get reply with all groups replies
			Request sendReply = tracker.getMergedReply();
			sendReply.setSender(groupId);
			// get all requests waiting for this answer
			ConcurrentSet<TOMMessage> msgs = globalReplies.get(sendReply.getClient()).get(sendReply.getSeqNumber());
			// add finished request result to map, for storage and eventual later
			// re-submission
			processedReplies.computeIfAbsent(sendReply.getClient(), k -> new ConcurrentHashMap<>());
			processedReplies.get(sendReply.getClient()).put(sendReply.getSeqNumber(), sendReply);
			// reply to all
			if (msgs != null) {
				System.out.println("replying");
				for (TOMMessage msg : msgs) {
					msg.reply.setContent(sendReply.toBytes());
					rc.getServerCommunicationSystem().send(new int[] { msg.getSender() }, msg.reply);
				}
			}
			// remove entries for processed and save reply
			globalReplies.get(req.getClient()).remove(sendReply.getSeqNumber());
			repliesTracker.get(req.getClient()).remove(sendReply.getSeqNumber());

		}
	}

	public Tree getOverlayTree() {
		return overlayTree;
	}

	public Vertex getMyVertex() {
		return me;
	}

	/////// TODO Override methods, maybe to fix, will look into it later

	@Override
	public void reset() {

	}

	@Override
	public byte[] executeOrderedFIFO(byte[] bytes, MessageContext messageContext, int i, int i1) {
		// System.out.println("FIFO");
		return bytes;
	}

	@Override
	public byte[] executeUnorderedFIFO(byte[] bytes, MessageContext messageContext, int i, int i1) {
		throw new UnsupportedOperationException("Universal replier only accepts ordered messages");
	}

	@Override
	public byte[] executeOrdered(byte[] bytes, MessageContext messageContext) {
		throw new UnsupportedOperationException("All ordered messages should be FIFO");
	}

	@Override
	public byte[] executeUnordered(byte[] bytes, MessageContext messageContext) {
		throw new UnsupportedOperationException("All ordered messages should be FIFO");
	}

//	@Override
//	public byte[][] executeBatch(byte[][] command, MessageContext[] msgCtx) {
//		// System.out.println("BATCH");
//
//		return command;
//	}
	
	
	public Request getMajreq(ConcurrentSet<TOMMessage> msgs, int majority) {
//		System.out.println("majjj    " +  majority);
//		System.out.println("msgs    " +  msgs.size());
		Request r, r2;
		for (TOMMessage m : msgs) {
			int count = 0;
			r = new Request(m.getContent());
			for (TOMMessage m2 : msgs) {
				r2 = new Request(m2.getContent());
				if (r.equals(r2)) {
					count++;
//					System.out.println(count);
					if(count >= majority) {
						return r;
					}
				}
			}
		}
		return null;
		
	}
}

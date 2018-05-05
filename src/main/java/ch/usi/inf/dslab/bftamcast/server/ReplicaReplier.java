
package ch.usi.inf.dslab.bftamcast.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
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
import ch.usi.inf.dslab.bftamcast.kvs.RequestType;
import ch.usi.inf.dslab.bftamcast.util.GroupRequestTracker;
import ch.usi.inf.dslab.bftamcast.util.RequestTracker;
import io.netty.util.internal.ConcurrentSet;

/**
 * @author Christian Vuerich - christian.vuerich@usi.ch
 *
 */
// TODO since BatchExecutable calls the same method as FIFOExecutable in
// performance try to remove BatchExecutable to check perf.
// public class ReplicaReplier implements Replier, FIFOExecutable,
// BatchExecutable, Serializable, ReplyListener {
public class ReplicaReplier implements Replier, Serializable, ReplyListener, FIFOExecutable, BatchExecutable {

	private static final long serialVersionUID = 1L;
	// keep the proxy of all groups and compute lca etc/
	private Tree overlayTree;
	private int batchsize = 10;
	private int sequencenumber = 0;
	private List<Request> toprocess = new ArrayList<>();
	private int groupId, maxOutstanding;
	protected transient Lock replyLock;
	protected transient Condition contextSet;
	protected transient ReplicaContext rc;
	protected Request req;

	// key store map
	private Map<Integer, byte[]> table;
	// trackers for replies from replicas
	private transient ConcurrentMap<Integer, ConcurrentHashMap<Integer, RequestTracker>> repliesTracker;
	// map for finished requests replies
	private transient ConcurrentMap<Integer, ConcurrentHashMap<Integer, Request>> processedReplies;
	// map for not processed requests
	private transient ConcurrentMap<Integer, ConcurrentHashMap<Integer, ConcurrentSet<TOMMessage>>> RequestWaiingToReachQuorumSize;
	// vertex in the overlay tree representing my group
	private Vertex me;
	// pending requests waiting for slot
	private transient Queue<RequestTracker> pendingRequests;

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
		System.out.println("max out = " + maxOutstanding);
		me = overlayTree.findVertexById(groupID);
		replyLock = new ReentrantLock();
		contextSet = replyLock.newCondition();
		RequestWaiingToReachQuorumSize = new ConcurrentHashMap<>();
		repliesTracker = new ConcurrentHashMap<>();
		processedReplies = new ConcurrentHashMap<>();
		pendingRequests = new LinkedBlockingQueue<>();

		table = new TreeMap<>();
	}

	@Override
	public void manageReply(TOMMessage request, MessageContext msgCtx) {
		System.out.println("call manage reply");
		handleMsg(request, false);

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
		Map<Integer, ConcurrentSet<TOMMessage>> map = RequestWaiingToReachQuorumSize.computeIfAbsent(clientID,
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

	public byte[] handleMsg(TOMMessage message, boolean reply) {
		if (reply) {
//			if (message == null) {
//
//			}
//			// System.out.println("recieved");
//			// unpack request from reply
//			Request replyReq = new Request(message.getContent());
//			// System.out.println(replyReq);
//			// get the tracker for that request
//			if (repliesTracker.get(replyReq.getClient()) == null) {
//				return;
//			}
//			RequestTracker tracker = repliesTracker.get(replyReq.getClient()).get(replyReq.getSeqNumber());
//			// add the reply to tracker and if all involved groups reached their f+1 quota
//			if (tracker != null && tracker.addReply(message, replyReq.getSender())) {
//
//				System.out.println(tracker.getElapsed() + "   nanoseconds");
//				// get reply with all groups replies
//				Request sendReply = tracker.getMergedReply();
//				sendReply.setSender(groupId);
//				// get all requests waiting for this answer
//				ConcurrentSet<TOMMessage> msgs = RequestWaiingToReachQuorumSize.get(sendReply.getClient())
//						.get(sendReply.getSeqNumber());
//				// add finished request result to map, for storage and eventual later
//				// re-submission
//				processedReplies.computeIfAbsent(sendReply.getClient(), k -> new ConcurrentHashMap<>());
//				processedReplies.get(sendReply.getClient()).put(sendReply.getSeqNumber(), sendReply);
//				// reply to all
//				if (msgs != null) {
//					// System.out.println("replying");
//					for (TOMMessage msg : msgs) {
//						msg.reply.setContent(sendReply.toBytes());
//						rc.getServerCommunicationSystem().send(new int[] { msg.getSender() }, msg.reply);
//					}
//				}
//				// remove entries for processed and save reply
//				RequestWaiingToReachQuorumSize.get(req.getClient()).remove(sendReply.getSeqNumber());
//				if (RequestWaiingToReachQuorumSize.get(req.getClient()).isEmpty()) {
//					RequestWaiingToReachQuorumSize.remove(req.getClient(),
//							RequestWaiingToReachQuorumSize.get(req.getClient()));
//				}
//				repliesTracker.get(req.getClient()).remove(sendReply.getSeqNumber());
//				if (repliesTracker.get(req.getClient()).isEmpty()) {
//					repliesTracker.remove(req.getClient(), repliesTracker.get(req.getClient()));
//				}
//
//				if (!pendingRequests.isEmpty()) {
//					System.out.println("other destination, queue empty, send");
//					RequestTracker reqtracker = pendingRequests.poll();
//					req = new Request(reqtracker.getRequest());
//					repliesTracker.computeIfAbsent(req.getClient(), k -> new ConcurrentHashMap<>());
//
//					repliesTracker.get(req.getClient()).put(req.getSeqNumber(), reqtracker);
//					reqtracker.start();
//					for (Vertex v : reqtracker.getGroups().keySet()) {
//						v.getProxy().invokeAsynchRequest(reqtracker.getRequest(), this, TOMMessageType.ORDERED_REQUEST);
//					}
//
//				}
//
//			}
		return null;
		} else {

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
			req = new Request(message.getContent());
			if(req.getType() == RequestType.BATCH) {
				List<Request> requests = new ArrayList<>();
				Request[] reqs = Request.ArrayfromBytes(req.getValue());
				 
			}
			else {
				
			}
			req.setSender(groupId);

			// already processes and answered request to other replicas, send what has
			// been done
			if (processedReplies.get(req.getClient()) != null
					&& processedReplies.get(req.getClient()).containsKey(req.getSeqNumber())) {
				System.out.println("cached answer send");
				message.reply.setContent(processedReplies.get(req.getClient()).get(req.getSeqNumber()).toBytes());
				rc.getServerCommunicationSystem().send(new int[] { message.getSender() }, message.reply);
			}
			// client contacted server directly, no majority needed
			else if (req.getDestination().length == 1) {
				System.out.println("only destination, send");

				execute(req);

				message.reply.setContent(req.toBytes());
				rc.getServerCommunicationSystem().send(new int[] { message.getSender() }, message.reply);
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
				if (groupId != overlayTree.getLca(overlayTree.getIdentifier(req.getDestination())).getID()) {
					majReplicasOfSender = me.getParent().getProxy().getViewManager().getCurrentViewF() + 1;
				}

				// save message
				ConcurrentSet<TOMMessage> msgs = saveRequest(message, req.getSeqNumber(), req.getClient());
				// check if majority of parent contacted me, and request is the same
				// -1 because the request used to compare other is already in msgs

				// majority of replicas sent request and this replica is not already
				// processing
				// the request (not processing it more than once)
				// count
				if (msgs.size() >= majReplicasOfSender && (repliesTracker.get(req.getClient()) == null
						|| !repliesTracker.get(req.getClient()).containsKey(req.getSeqNumber()))) {

					req = GroupRequestTracker.getMajreq(msgs, majReplicasOfSender);
					req.setSender(groupId);
					// System.out.println("asdflkhjadsfdka " + req);
					boolean addreq = false;
					Map<Vertex, Integer> toSend = new HashMap<>();
					// List<Vertex>
					Set<Vertex> involved = overlayTree.getRoute(req.getDestIdentifier(), me);
					if (involved.contains(me)) {
						execute(req);
						addreq = true;
					}
					for (Vertex dest : involved) {
						if (dest != me) {
							toSend.put(dest, dest.getProxy().getViewManager().getCurrentViewF() + 1);
						}
					}

					// no other destination is in my reach, send reply back
					if (toSend.keySet().isEmpty() && addreq) {
						System.out.println("only destination, send");
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
						RequestWaiingToReachQuorumSize.get(req.getClient()).remove(req.getSeqNumber());
						return req.toBytes();
					} else {
						RequestTracker tracker = null;
						// else, tracker for received replies and majority needed
						// add map for a client tracker if absent

						if (addreq) {
							tracker = new RequestTracker(toSend, req, message.getContent());

						} else {
							tracker = new RequestTracker(toSend, null, message.getContent());

						}
						// if pending empty send

						// if (repliesTracker.size() < maxOutstanding) {
						tracker.start();
						// repliesTracker.computeIfAbsent(req.getClient(), k -> new
						// ConcurrentHashMap<>());
						//
						// System.out.println("other destination, queue empty, send");
						//
						// repliesTracker.get(req.getClient()).put(req.getSeqNumber(),
						// tracker);
						List<Thread> threads = new ArrayList<>();
						List<Request> answers = new ArrayList<>();
						for (Vertex v : toSend.keySet()) {
							threads.add(new Thread(
									() -> answers.add(new Request(v.getProxy().invokeOrdered(message.getContent())))));
							threads.get(threads.size() - 1).start();

							// v.getProxy().invokeAsynchRequest(message.getContent(), this,
							// TOMMessageType.ORDERED_REQUEST);
						}

						for (Thread t : threads) {
							try {
								t.join();
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}

						// merge replies
						Request res = tracker.getMergedReplyFromList(answers);

						res.setSender(groupId);
						// get all requests waiting for this answer
						ConcurrentSet<TOMMessage> msgs2 = RequestWaiingToReachQuorumSize.get(res.getClient())
								.get(res.getSeqNumber());
						// add finished request result to map, for storage and eventual later
						// re-submission
						processedReplies.computeIfAbsent(res.getClient(), k -> new ConcurrentHashMap<>());
						processedReplies.get(res.getClient()).put(res.getSeqNumber(), res);
						// reply to all
						if (msgs2 != null) {
							// System.out.println("replying");
							for (TOMMessage msg : msgs2) {
								msg.reply.setContent(res.toBytes());
								rc.getServerCommunicationSystem().send(new int[] { msg.getSender() }, msg.reply);
							}
						}
						return res.toBytes();

						// }
						// else {
						// System.out.println("save to process later");
						// pendingRequests.add(tracker);
						// }
					}

				}
			}

		}
		return null;
	}

	/**
	 * Async reply reciever
	 */
	@Override
	public void replyReceived(RequestContext context, TOMMessage reply) {
		System.out.println("magic");
		handleMsg(reply, true);
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

	@Override
	public byte[][] executeBatch(byte[][] command, MessageContext[] msgCtx) {
		if (me == overlayTree.getRoot()) {
			List<Request> reqs = new ArrayList<>();

			for (int i = 0; i < reqs.size(); i++) {
				Request r = new Request(command[i]);
				r.setSender(me.ID);
				reqs.add(r);
			}

			// mainReq.setValue(Request.ArrayToBytes(reqs));

			return batch(reqs);

		} else {

			return command;
		}
	}

	public byte[][] batch(List<Request> tobatch) {
		List<Thread> threads = new ArrayList<>();
		Request mainReq = new Request(RequestType.BATCH, -1, Request.ArrayToBytes(tobatch), new int[0], sequencenumber++, me.ID,
				me.ID, -11l);
		byte[][] batchReplies = new byte[tobatch.size()][];
		int i = 0;
		for (Vertex connection : me.connections) {
			final int ii = i;
			threads.add(new Thread(() -> batchReplies[ii] = connection.getProxy().invokeOrdered(mainReq.toBytes())));
			i++;

		}

		for (Thread t : threads) {
			t.start();
		}

		for (Thread t : threads) {
			try {
				t.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		return batchReplies;

	}

}

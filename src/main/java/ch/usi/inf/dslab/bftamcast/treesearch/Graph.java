/**
 * 
 */
package ch.usi.inf.dslab.bftamcast.treesearch;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

/**
 * @author Christian Vuerich - christian.vuerich@usi.ch
 *
 */
public class Graph {
	public List<Vertex> vertices = new ArrayList<>();
	public List<DestSet> load = new ArrayList<>();

	// instead have a sorter for each destination combo
	public static void main(String[] args) {
		new Graph("config/load.conf");
	}

	public Graph(String configFile) {

		FileReader fr;
		BufferedReader rd;

		try {
			fr = new FileReader(configFile);

			rd = new BufferedReader(fr);
			String line = null;
			while ((line = rd.readLine()) != null) {
				if (!line.startsWith("#") && !line.isEmpty()) {
					StringTokenizer str;
					if (line.contains("%")) {
						str = new StringTokenizer(line, "%");
						if (str.countTokens() == 2) {
							int loadp = Integer.valueOf(str.nextToken());
							List<Vertex> ver = new ArrayList<>();
							str = new StringTokenizer(str.nextToken(), " ");
							while (str.hasMoreTokens()) {
								int id = Integer.valueOf(str.nextToken());
								for (Vertex v : vertices) {
									if (v.ID == id) {
										ver.add(v);
										break;
									}
								}
							}
							DestSet s = new DestSet(loadp, ver);
							load.add(s);
						}
					} else {
						str = new StringTokenizer(line, " ");
						// vertex declaration (group)
						if (str.countTokens() == 3) {
							Vertex v = new Vertex(Integer.valueOf(str.nextToken()), str.nextToken(),
									Integer.valueOf(str.nextToken()));
							vertices.add(v);
						}

					}
				}

			}
			fr.close();
			rd.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// generate all possible destinations (to clean up)
		List<Integer> tmp = new ArrayList<>();
		int arr[] = new int[vertices.size()];
		int i = 0;
		for (Vertex v : vertices) {
			arr[i] = v.ID;
			i++;
		}
		int n = arr.length;
		int N = (int) Math.pow(2d, Double.valueOf(n));
		for (int i1 = 1; i1 < N; i1++) {
			String code = Integer.toBinaryString(N | i1).substring(1);
			for (int j = 0; j < n; j++) {
				if (code.charAt(j) == '1') {
					tmp.add(arr[j]);
				}
			}
			if (!existsLoad(tmp)) {
				List<Vertex> ggCode = new ArrayList<>();
				for (Vertex vertex : vertices) {
					if (tmp.contains(vertex.ID)) {
						ggCode.add(vertex);
					}
				}
				load.add(new DestSet(1, ggCode));
			}
			tmp.clear();
		}
		System.out.println("RIP " + load.size() + " " + N);

		// print destinations and loads %

		for (DestSet s : load) {
			System.out.print(s.percentage + "% ");
			for (Vertex v : s.destinations) {
				System.out.print(v.ID + " ");
			}
			System.out.println();
		}

		// find overlapping sets of destinations (find prefix order)

		for (DestSet s : load) {
			for (DestSet s2 : load) {
				if (s2 != s && !Collections.disjoint(s.destinationsIDS, s2.destinationsIDS)) {
					if (!s2.overlaps.contains(s)) {
						s2.overlaps.add(s);
					}
					if (!s.overlaps.contains(s2)) {
						s.overlaps.add(s2);
					}
				}
			}

		}
		Vertex root = null;
		load.sort(new DestSet(0, null));
		for (DestSet s : load) {
			System.out.println(s.percentage);
		}

		// find optimal genuine node to sort for each destination set
		for (DestSet s : load) {

			Vertex maxCapacityVertex = null;
			double max = 0;
			for (Vertex v : s.destinations) {
				if (v.resCapacity > max) {
					max = v.resCapacity;
					maxCapacityVertex = v;
				}
			}
			s.root = maxCapacityVertex;
			System.out
					.println("1111MAX = " + maxCapacityVertex.ID + " res capacity = " + maxCapacityVertex.resCapacity);

			// TODO if another load overlaps either use it's root or use common node as
			// root
			// for current load or external if genuine are overloaded
			maxCapacityVertex.resCapacity = maxCapacityVertex.resCapacity
					+ (maxCapacityVertex.capacity * (s.percentage / 100.0));

		}

		// handle prefix order for overlapping
		//
		// for (DestSet s : load) {
		// // genuine for single target
		//
		// List<Vertex> possibleRoots = new ArrayList<>();
		// possibleRoots.add(s.root);
		// for (DestSet overlap : s.overlaps) {
		// possibleRoots.add(overlap.root);
		// overlap.root.resCapacity = overlap.root.resCapacity
		// - (overlap.root.capacity * (overlap.percentage / 100.0));
		// for (Vertex v : overlap.destinations) {
		// if (s.destinations.contains(v)) {
		// possibleRoots.remove(v);
		// possibleRoots.add(v);
		// }
		// }
		// }
		//
		// Vertex maxCapacityVertex = null;
		// double max = 0;
		// for (Vertex v : possibleRoots) {
		// if (v.resCapacity > max) {
		// max = v.resCapacity;
		// maxCapacityVertex = v;
		// }
		// }
		// if (maxCapacityVertex != null) {
		// s.root = maxCapacityVertex;
		// for (DestSet overlap : s.overlaps) {
		// overlap.root = maxCapacityVertex;
		//
		// }
		// System.out
		// .println("MAX = " + maxCapacityVertex.ID + " res capacity = " +
		// maxCapacityVertex.resCapacity);
		//
		// maxCapacityVertex.resCapacity = maxCapacityVertex.resCapacity
		// - (maxCapacityVertex.capacity * (s.percentage / 100.0));
		// } else {
		// for (DestSet overlap : s.overlaps) {
		// possibleRoots.add(overlap.root);
		// overlap.root.resCapacity = overlap.root.resCapacity
		// - (overlap.root.capacity * (overlap.percentage / 100.0));
		// }
		//
		// // TODO if another load overlaps either use it's root or use common node as
		// // root
		// // for current load or external if genuine are overloaded
		// //check acyclic
		//
		// }
		// }

		// sort by load % higher load shallower tree (less communication delays)

		int size = vertices.size();
		while (size >= 2) {
			for (DestSet s : load) {
				if (s.destinations.size() == size) {
					System.out.println(s.root.ID + "  " + s.destinations.toString());

					if (root == null || s.root == root) {
						root = s.root;
						root.connections.removeAll(s.destinations);
						root.connections.addAll(s.destinations);
						root.connections.remove(s.root);
					} else {
							Vertex v = findParent(s.root);
							System.out.println(v.ID);
							v.connections.removeAll(s.destinations);
							v.connections.add(s.root);
							s.root.connections.addAll(s.destinations);
							s.root.connections.remove(s.root);
					}

				}
			}
			size--;
		}

		for (

		DestSet s : load) {
			getRoot(s.destinationsIDS);
		}

		PrintWriter writer;
		try {
			for (DestSet s : load) {
				String gg = "digraph G { ";
				String d = "";
				for (Vertex v : s.destinations) {
					d += v.ID + "_";
					if (true) {
						if (!gg.contains("" + s.root.ID + "->" + v.ID + "\n")) {
							gg += "" + s.root.ID + "->" + v.ID + "\n";
							v.printed = true;
						}
					}
				}
				gg += "}";

				writer = new PrintWriter("graphs/graph_" + d + ".dot", "UTF-8");
				writer.println(gg);
				writer.close();
			}

			String gg = "digraph G { ";
			for (Vertex v : vertices) {
				for (Vertex d : v.connections) {
					if (!gg.contains("" + v.ID + "->" + d.ID + "\n")) {
						gg += "" + v.ID + "->" + d.ID + "\n";
					}
				}
			}

			gg += "}";
			writer = new PrintWriter("graphs/graph_total.dot", "UTF-8");
			writer.println(gg);
			writer.close();

		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public Vertex getRoot(List<Integer> dests) {
		System.out.print("List " + dests.toString() + "  has root ");
		for (DestSet s : load) {
			if (s.matchDests(dests)) {
				System.out.println(s.root.ID + " here");
				return s.root;
			}
		}

		double max = 0;
		Vertex maxV = null;
		for (Vertex v : vertices) {
			if (dests.contains(v.ID) && v.resCapacity < max) {
				max = v.resCapacity;
				maxV = v;
			}
		}
		System.out.println(maxV.ID + " there");

		return maxV;
	}

	public boolean existsLoad(List<Integer> dests) {
		for (DestSet s : load) {
			if (s.matchDests(dests)) {
				return true;
			}
		}
		return false;
	}

	public Vertex findParent(Vertex g) {
		for (Vertex v : vertices) {
			if (v.connections.contains(g)) {
				return v;
			}
		}
		return null;
	}

}

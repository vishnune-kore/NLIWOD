package org.aksw.hawk.controller;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aksw.autosparql.commons.qald.QALD4_EvaluationUtils;
import org.aksw.autosparql.commons.qald.QALD_Loader;
import org.aksw.autosparql.commons.qald.Question;
import org.aksw.hawk.cache.CachedParseTree;
import org.aksw.hawk.nlp.MutableTreeNode;
import org.aksw.hawk.nlp.MutableTreePruner;
import org.aksw.hawk.nlp.SentenceToSequence;
import org.aksw.hawk.nlp.spotter.ASpotter;
import org.aksw.hawk.nlp.spotter.Fox;
import org.aksw.hawk.querybuilding.Annotater;
import org.aksw.hawk.querybuilding.SPARQL;
import org.aksw.hawk.querybuilding.SPARQLQuery;
import org.aksw.hawk.querybuilding.SPARQLQueryBuilder;
import org.aksw.hawk.ranking.VotingBasedRanker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.hp.hpl.jena.rdf.model.RDFNode;

public abstract class Pipeline {
	static Logger log = LoggerFactory.getLogger(Pipeline.class);
	protected String dataset;
	protected QALD_Loader datasetLoader;
	protected ASpotter nerdModule;
	protected CachedParseTree cParseTree;
	protected MutableTreePruner pruner;
	protected SentenceToSequence sentenceToSequence;
	protected Annotater annotater;
	protected SPARQLQueryBuilder queryBuilder;
	protected VotingBasedRanker ranker;

	public Pipeline() {

		datasetLoader = new QALD_Loader();

		// ASpotter wiki = new WikipediaMiner();
		// controller.nerdModule = new MultiSpotter(fox, tagMe, wiki, spot);
		nerdModule = new Fox();
		// controller.nerdModule = new Spotlight();
		// controller.nerdModule =new TagMe();
		// controller.nerdModule = new WikipediaMiner();

		cParseTree = new CachedParseTree();

		sentenceToSequence = new SentenceToSequence();

		pruner = new MutableTreePruner();

		SPARQL sparql = new SPARQL();
		annotater = new Annotater(sparql);
		queryBuilder = new SPARQLQueryBuilder(sparql);

		ranker = new VotingBasedRanker();

	}

	abstract void modus(Question q, Set<SPARQLQuery> queries);

	abstract Map<String, Answer> buildQuery(Question q);

	void run(Set<EvalObj> evals) throws IOException {
		// 1. read in Questions from QALD 4
		List<Question> questions = datasetLoader.load(dataset);
		double overallf = 0;
		double overallp = 0;
		double overallr = 0;
		double counter = 0;
		for (Question q : questions) {
			if (q.answerType.equals("resource")) {
				if (q.onlydbo) {
					if (!q.aggregation) {
						String question = q.languageToQuestion.get("en");
						Map<String, Answer> answer = calculateSPARQLRepresentation(q);

						double fmax = 0;
						double pmax = 0;
						double rmax = 0;
						Set<SPARQLQuery> correctQueries = Sets.newHashSet();
						for (String query : answer.keySet()) {
							Set<RDFNode> systemAnswers = answer.get(query).answerSet;
							// 11. Compare to set of resources from benchmark
							double precision = QALD4_EvaluationUtils.precision(systemAnswers, q);
							double recall = QALD4_EvaluationUtils.recall(systemAnswers, q);
							double fMeasure = QALD4_EvaluationUtils.fMeasure(systemAnswers, q);
							if (fMeasure >= fmax && fMeasure > 0) {
								log.info(query.toString());
								log.info("\tP=" + precision + " R=" + recall + " F=" + fMeasure);
								if (fMeasure > fmax) {
									// used if query with score of 0.4 is in set
									// and a new one with 0.6 comes in to save
									// only worthy queries with constant
									// f-measure
									correctQueries.clear();
								}
								fmax = fMeasure;
								pmax = precision;
								rmax = recall;
								correctQueries.add(answer.get(query).query);
							}
						}
						modus(q, correctQueries);
						evals.add(new EvalObj(question, fmax, pmax, rmax, "Assuming Optimal Ranking Function, Spotter: " + nerdModule.toString()));
						overallf += fmax;
						overallp += pmax;
						overallr += rmax;
						counter++;
						log.info("########################################################");
					} else {
						// evals.add(new EvalObj(question,0, 0, 0,
						// "This question askes for aggregation (ASK)"));
					}
				} else {
					// evals.add(new EvalObj(question,0, 0, 0,
					// "This question askes for yago types"));
				}
			} else {
				// evals.add(new EvalObj(question,0, 0, 0,
				// "This is no question asking for resources only"));
			}
			// break;
		}

		log.info("Average P=" + overallp / counter + " R=" + overallr / counter + " F=" + overallf / counter + " Counter=" + counter);
	}

	public Map<String, Answer> calculateSPARQLRepresentation(Question q) {
		log.info(q.languageToQuestion.get("en"));
		// 2. Disambiguate parts of the query
		q.languageToNamedEntites = nerdModule.getEntities(q.languageToQuestion.get("en"));

		// 3. Build trees from questions and cache them
		q.tree = cParseTree.process(q);
		log.info("" + q.tree);

		q.cardinality = cardinality(q);
		// noun combiner, decrease #nodes in the DEPTree decreases
		sentenceToSequence.combineSequences(q);

		// 4. Apply pruning rules
		q.tree = pruner.prune(q);

		// 5. Annotate tree
		annotater.annotateTree(q);
		log.info(q.tree.toString());

		Map<String, Answer> answer = buildQuery(q);
		return answer;
	}

	protected void write(Set<EvalObj> evals) {
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter("results.html"));
			bw.write("<script src=\"sorttable.js\"></script><table class=\"sortable\">");
			bw.newLine();
			bw.write(" <tr>     <th>Question</th><th>F-measure</th><th>Precision</th><th>Recall</th><th>Comment</th>  </tr>");
			for (EvalObj eval : evals) {
				bw.write(" <tr>    <td>" + eval.getQuestion() + "</td><td>" + eval.getFmax() + "</td><td>" + eval.getPmax() + "</td><td>" + eval.getRmax() + "</td><td>" + eval.getComment() + "</td>  </tr>");
				bw.newLine();
			}
			bw.write("</table>");
			bw.newLine();
			bw.close();
		} catch (IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	protected int cardinality(Question q) {
		// look at the first child of root and determine the quality based on
		// the POS Tag
		int cardinality = 12;
		MutableTreeNode root = q.tree.getRoot();
		// IN because of "In which..."
		if (root.posTag.matches("VB(.)*")) {
			MutableTreeNode firstChild = root.children.get(0);
			String posTag = firstChild.posTag;
			if (posTag.equals("NNS")) {
				cardinality = 12;
			} else if (posTag.matches("WP||WRB||ADD||NN||VBZ||IN")) {
				cardinality = 1;
			} else if (posTag.matches("IN")) {
				MutableTreeNode secondChild = firstChild.getChildren().get(0);
				posTag = secondChild.posTag;
				if (posTag.equals("NN")) {
					cardinality = 1;
				} else {
					cardinality = 12;
				}
			} else {
				cardinality = 12;
			}

		} else {
			String posTag = root.posTag;
			if (posTag.matches("NNS||NNP(.)*")) {
				cardinality = 12;
			} else {
				cardinality = 1;
			}
		}
		return cardinality;
	}

}

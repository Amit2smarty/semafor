/*******************************************************************************
 * Copyright (c) 2011 Dipanjan Das 
 * Language Technologies Institute, 
 * Carnegie Mellon University, 
 * All Rights Reserved.
 *
 * FeatureExtractor.java is part of SEMAFOR 2.0.
 *
 * SEMAFOR 2.0 is free software: you can redistribute it and/or modify  it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or 
 * (at your option) any later version.
 *
 * SEMAFOR 2.0 is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. 
 *
 * You should have received a copy of the GNU General Public License along
 * with SEMAFOR 2.0.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package edu.cmu.cs.lti.ark.fn.identification;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import edu.cmu.cs.lti.ark.fn.data.prep.formats.Sentence;
import edu.cmu.cs.lti.ark.util.IFeatureExtractor;
import edu.cmu.cs.lti.ark.util.ds.map.IntCounter;
import edu.cmu.cs.lti.ark.util.nlp.parse.DependencyParse;
import gnu.trove.TIntDoubleHashMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static edu.cmu.cs.lti.ark.fn.data.prep.formats.AllLemmaTags.*;

/**
 * Extracts features for the frame identification model
 */
public class FeatureExtractor implements IFeatureExtractor {
	private static final Joiner SPACE = Joiner.on(" ");
	private static final Joiner UNDERSCORE = Joiner.on("_");

	public Map<String, Map<String, Double>> extractFeaturesByName(Iterable<String> frameNames,
																  int[] targetTokenIdxs,
																  Sentence sentence) {
		// Get lemmas and postags for target
		final IntCounter<String> baseFeatures = getBaseFeatures(targetTokenIdxs, sentence);
		final Map<String, Map<String, Double>> results = Maps.newHashMap();
		// conjoin base features with frame
		for (String frame : frameNames) {
			final String frameFtr = "f:" + frame;
			final Map<String, Double> featuresForFrame = Maps.newHashMap();
			for (String feature : baseFeatures.keySet()) {
				featuresForFrame.put(
						SPACE.join(frameFtr, feature),
						baseFeatures.get(feature).doubleValue()
				);
			}
			results.put(frame, featuresForFrame);
		}
		return results;
	}

	private IntCounter<String> getBaseFeatures(int[] targetTokenIdxs,
											   Sentence sentence) {
		final String[][] allLemmaTags = sentence.toAllLemmaTagsArray();
		final DependencyParse parse = DependencyParse.processFN(allLemmaTags, 0.0);
		Arrays.sort(targetTokenIdxs);
		final List<String> tokenAndCpostags = Lists.newArrayListWithExpectedSize(targetTokenIdxs.length);
		final List<String> tokens = Lists.newArrayListWithExpectedSize(targetTokenIdxs.length);
		final List<String> cpostags = Lists.newArrayListWithExpectedSize(targetTokenIdxs.length);
		final List<String> lemmaAndCpostags = Lists.newArrayListWithExpectedSize(targetTokenIdxs.length);
		for (int tokenIdx : targetTokenIdxs) {
			final String form = allLemmaTags[PARSE_TOKEN_ROW][tokenIdx];
			final String postag = allLemmaTags[PARSE_POS_ROW][tokenIdx].toUpperCase();
			final String cpostag = getCpostag(postag);
			final String lemma = allLemmaTags[PARSE_LEMMA_ROW][tokenIdx];
			tokens.add(form);
			tokenAndCpostags.add(form + "_" + cpostag);
			cpostags.add(cpostag);
			lemmaAndCpostags.add(lemma + "_" + cpostag);
		}
		final String cpostagsStr = UNDERSCORE.join(cpostags);
		final String lemmaAndCpostagsStr = UNDERSCORE.join(lemmaAndCpostags);

		final IntCounter<String> featureMap = new IntCounter<String>();
		featureMap.increment("aP:" + cpostagsStr);
		featureMap.increment("aLP:" + lemmaAndCpostagsStr);

//		// add a feature for each word in the sentence
//		for (int tokenIdx : xrange(allLemmaTags[0].length)) {
//			final String form = allLemmaTags[PARSE_TOKEN_ROW][tokenIdx];
//			final String postag = allLemmaTags[PARSE_POS_ROW][tokenIdx].toUpperCase();
//			final String cpostag = getCpostag(postag);
//			final String lemma = parseHasLemmas ? allLemmaTags[PARSE_LEMMA_ROW][tokenIdx]
//					: lemmatizer.getLowerCaseLemma(form, postag);
//			featureMap.increment("sTP:" + form + "_" + cpostag);
//			featureMap.increment("sLP:" + lemma + "_" + cpostag);
//		}

		/*
		 * syntactic features
		 */
		final DependencyParse[] sortedNodes = parse.getIndexSortedListOfNodes();
		final DependencyParse head = DependencyParse.getHeuristicHead(sortedNodes, targetTokenIdxs);
		final String headCpostag = getCpostag(head.getPOS());

		final List<DependencyParse> children = head.getChildren();

		final SortedSet<String> depLabels = Sets.newTreeSet(); // unordered set of arc labels of children
		for (DependencyParse child : children) {
			depLabels.add(child.getLabelType().toUpperCase());
		}
		featureMap.increment("d:" + UNDERSCORE.join(depLabels));

		if (headCpostag.equals("V")) {
			final List<String> subcat = Lists.newArrayListWithExpectedSize(children.size()); // ordered arc labels of children
			for (DependencyParse child : children) {
				final String labelType = child.getLabelType().toUpperCase();
				if (!labelType.equals("SUB") && !labelType.equals("P") && !labelType.equals("CC")) {
					// TODO(smt): why exclude "sub"?
					subcat.add(labelType);
				}
			}
			featureMap.increment("sC:" + UNDERSCORE.join(subcat));
		}

		final DependencyParse parent = head.getParent();
		final String parentPos = ((parent == null) ? "NULL" : parent.getPOS().toUpperCase());
		featureMap.increment("pP:" + parentPos);
		final String parentLemma = ((parent == null) ? "NULL" : parent.getLemma());
		featureMap.increment("pPL:" + parentPos + "_" + parentLemma);
		final String parentLabel = ((parent == null) ? "NULL" : parent.getLabelType().toUpperCase());
		featureMap.increment("pL:" + parentLabel);
		return featureMap;
	}

	public Map<String, TIntDoubleHashMap> extractFeaturesByIndex(Iterable<String> frames,
																 int[] targetTokenIdxs,
																 Sentence sentence,
																 Map<String, Integer> alphabet) {
		final Map<String, Map<String, Double>> featuresByFrame = extractFeaturesByName(
				frames,
				targetTokenIdxs,
				sentence
		);
		// replace String feature names with feature indexes
		return convertToIndexes(featuresByFrame, alphabet);

	}

	public static Map<String, TIntDoubleHashMap> convertToIndexes(Map<String, Map<String, Double>> featuresByFrame,
																  Map<String, Integer> alphabet) {
		final Map<String, TIntDoubleHashMap> results = Maps.newHashMap();
		for (String frame : featuresByFrame.keySet()) {
			final Map<String, Double> features = featuresByFrame.get(frame);
			final TIntDoubleHashMap featsForFrame = new TIntDoubleHashMap(features.size());
			for (String feat : features.keySet()) {
				if (alphabet.containsKey(feat)) {
					featsForFrame.put(alphabet.get(feat), features.get(feat));
				}
			}
			results.put(frame, featsForFrame);
		}
		return results;
	}

	private static String getCpostag(String postag) {
		return postag.substring(0, 1).toUpperCase();
	}
}

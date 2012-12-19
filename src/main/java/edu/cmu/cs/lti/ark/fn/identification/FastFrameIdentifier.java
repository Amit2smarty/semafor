/*******************************************************************************
 * Copyright (c) 2011 Dipanjan Das 
 * Language Technologies Institute, 
 * Carnegie Mellon University, 
 * All Rights Reserved.
 * 
 * FastFrameIdentifier.java is part of SEMAFOR 2.0.
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


import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import edu.cmu.cs.lti.ark.fn.data.prep.formats.Sentence;
import edu.cmu.cs.lti.ark.fn.data.prep.formats.Token;
import edu.cmu.cs.lti.ark.util.ds.Pair;
import edu.cmu.cs.lti.ark.util.ds.map.IntCounter;
import edu.cmu.cs.lti.ark.util.nlp.parse.DependencyParse;
import edu.cmu.cs.lti.ark.util.optimization.LogFormula;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TObjectDoubleHashMap;

import javax.annotation.Nullable;
import java.util.*;

import static edu.cmu.cs.lti.ark.fn.data.prep.formats.AllLemmaTags.*;
import static edu.cmu.cs.lti.ark.fn.identification.ScanAdverbsAndAdjectives.getCanonicalForm;

/**
 * @author dipanjan
 * This class is for finding the best frame for a span of tokens, given 
 * the annotation for a sentence. It is just a single node implementation. 
 * Moreover, it looks only at a small set of frames for seen words
 */
public class FastFrameIdentifier extends LRIdentificationModelSingleNode
{
	private THashMap<String, THashSet<String>> mHvCorrespondenceMap;
	private Map<String, Set<String>> mRelatedWordsForWord;
	private THashMap<String,THashSet<String>> clusterMap;
	private Map<String, Map<String, Set<String>>> mRevisedRelationsMap;
	private Map<String, String> mHVLemmas;
	private int K;
	
	public FastFrameIdentifier(TObjectDoubleHashMap<String> paramList,
							   String reg,
							   double l,
							   THashMap<String, THashSet<String>> frameMap,
							   THashMap<String, THashSet<String>> hvCorrespondenceMap,
							   Map<String, Set<String>> relatedWordsForWord,
							   Map<String, Map<String, Set<String>>> revisedRelationsMap,
							   Map<String, String> hvLemmas)
	{
		super(paramList,reg,l,null,frameMap);
		initializeParameterIndexes();
		this.mParamList=paramList;
		mReg=reg;
		mLambda=l;
		mFrameMap=frameMap;
		totalNumberOfParams=paramList.size();
		initializeParameters();
		mLookupChart = new TIntObjectHashMap<LogFormula>();
		mHvCorrespondenceMap = hvCorrespondenceMap;
		mRelatedWordsForWord = relatedWordsForWord;
		mRevisedRelationsMap = revisedRelationsMap;
		mHVLemmas = hvLemmas;
	}
	
	private double getNumeratorValue(String frameName, int[] intTokNums, String[][] data) {
		m_current = 0;
		m_llcurrent = 0;
		return getValueForFrame(frameName, intTokNums, data);
	}

	private double getValueForFrame(String frame, int[] intTokNums, String[][] data) {
		THashSet<String> hiddenUnits = mFrameMap.get(frame);
		double result = 0.0;
		DependencyParse parse = DependencyParse.processFN(data, 0.0);
		for (String unit : hiddenUnits)
		{
			IntCounter<String> valMap = null;
			FeatureExtractor featex = new FeatureExtractor();
			if(clusterMap==null) {
				valMap =
					featex.extractFeaturesLessMemory(frame,
							intTokNums,
							unit,
							data,
							"test",
							mRelatedWordsForWord,
							mRevisedRelationsMap,
							mHVLemmas,
							parse);
			} else { // not supported
				valMap =
					featex.extractFeaturesWithClusters(frame, intTokNums, unit, data, mWNR, "test", null, null,parse,clusterMap,K);
			}
			Set<String> features = valMap.keySet();
			double featSum = 0.0;
			for (String feat : features)
			{
				double val = valMap.getT(feat);
				int ind = localA.get(feat);
				double paramVal = V[ind].exponentiate();
				double prod = val*paramVal;
				featSum+=prod;
			}
			double expVal = Math.exp(featSum);
			result+=expVal;
		}
		return result;
	}

	public Set<String> checkPresenceOfTokensInMap(int[] intTokNums, String[][] parseData) {
		final List<String> lemmatizedTokens = Lists.newArrayList();
		for (final int tokNum : intTokNums) {
			lemmatizedTokens.add(parseData[PARSE_LEMMA_ROW][tokNum]);
		}
		return mHvCorrespondenceMap.get(Joiner.on(" ").join(lemmatizedTokens));
	}

	public void setClusterInfo(THashMap<String,THashSet<String>> clusterMap,int K)
	{
		this.clusterMap=clusterMap;
		this.K=K;
	}
	
	
	public String[] getBestFrame(String frameLine, String parseLine, boolean printConf)
	{
		if (!printConf) {
			return new String[] {getBestFrame(frameLine, parseLine)};
		}
		double maxVal = -Double.MIN_VALUE;
		String[] toks = frameLine.split("\t");
		String[] tokNums = toks[1].split("_");
		int[] intTokNums = new int[tokNums.length];
		for(int j = 0; j < tokNums.length; j ++)
			intTokNums[j] = new Integer(tokNums[j]);
		Arrays.sort(intTokNums);
		StringTokenizer st = new StringTokenizer(parseLine,"\t");
		int tokensInFirstSent = new Integer(st.nextToken());
		String[][] data = new String[6][tokensInFirstSent];
		for(int k = 0; k < 6; k ++)
		{
			data[k]=new String[tokensInFirstSent];
			for(int j = 0; j < tokensInFirstSent; j ++)
			{
				data[k][j]=""+st.nextToken().trim();
			}
		}	
		Set<String> set = checkPresenceOfTokensInMap(intTokNums,data);
		if(set==null) {
			set = mFrameMap.keySet();
		}		
		double sum = 0.0;
		int count = 0;
		Pair<String, Double>[] frames = new Pair[set.size()];
		for(String frame: set)
		{
			double val =  getNumeratorValue(frame, intTokNums, data);
			frames[count] = new Pair<String, Double>(frame, val);
			count++;
 			sum += val;
			//System.out.println("Considered "+frame+" for frameLine:"+frameLine);
		}
		Comparator<Pair<String, Double>> c = new Comparator<Pair<String, Double>>() {
			public int compare(Pair<String, Double> arg0,
					Pair<String, Double> arg1) {
				if (arg0.getSecond() > arg1.getSecond()) {
					return -1;
				} else if (arg0.getSecond() == arg1.getSecond()) {
					return 0;
				} else { 
					return 1;
				}
			}			
		};	
		Arrays.sort(frames, c);
		int K = frames.length < 10 ? frames.length : 10;
		String[] results = new String[K];
		for (int i = 0; i < K; i ++) {
			results[i] = frames[i].getFirst() + "\t" + (frames[i].getSecond() / sum);
  		}
		return results;
	}	
	
	public String getBestFrame(String frameLine, String parseLine, SmoothedGraph sg) {
		String[] toks = frameLine.split("\t");
		String[] tokNums = toks[1].split("_");
		int[] intTokNums = new int[tokNums.length];
		for(int j = 0; j < tokNums.length; j ++) {
			intTokNums[j] = Integer.parseInt(tokNums[j]);
		}
		Arrays.sort(intTokNums);

		return getBestFrame(intTokNums, Sentence.fromAllLemmaTagsArray(readLine(parseLine)), sg);
	}

	public String getBestFrame(int[] tokenIndices, Sentence sentence, SmoothedGraph graph) {
		Set<String> candidateFrames = getCandidateFrames(tokenIndices, sentence, graph);
		String[][] parseData = sentence.toAllLemmaTagsArray();
		String result = null;
		double maxVal = -Double.MIN_VALUE;
		// get max
		for(String frame : candidateFrames) {
			double val =  getNumeratorValue(frame, tokenIndices, parseData);
			if(val > maxVal) {
				maxVal = val;
				result = "" + frame;
			}
		}
		return result;
	}

	private Set<String> getCandidateFrames(int[] tokenIndices, Sentence sentence, SmoothedGraph graph) {
		final List<Token> sentenceTokens = sentence.getTokens();

		final List<Token> frameTokens = Lists.newArrayList();
		final List<String> lowerCaseForms = Lists.newArrayList();
		final List<String> lemmaList = Lists.newArrayList();
		for(int tokNum : tokenIndices) {
			final Token token = sentenceTokens.get(tokNum);
			frameTokens.add(token);
			lowerCaseForms.add(token.getForm().toLowerCase());
			lemmaList.add(token.getLemma());
		}
		final String lemmas = Joiner.on(" ").join(lemmaList);

		if(mHvCorrespondenceMap.containsKey(lemmas)) return mHvCorrespondenceMap.get(lemmas);

		final Map<String, Set<String>> coarseMap = graph.getCoarseMap();
		if (frameTokens.size() > 1) {
			final String coarseToken = getCanonicalForm(Joiner.on(" ").join(lowerCaseForms));
			if (coarseMap.containsKey(coarseToken)) return coarseMap.get(coarseToken);
		} else {
			final Token token = frameTokens.get(0);
			String pos = Strings.nullToEmpty(token.getPostag());
			if (pos.startsWith("N")) {
				pos = "n";
			} else if (pos.startsWith("V")) {
				pos = "v";
			} else if (pos.startsWith("J")) {
				pos = "a";
			} else if (pos.startsWith("RB")) {
				pos = "adv";
			} else if (pos.startsWith("I") || pos.startsWith("TO")) {
				pos = "prep";
			} else {
				pos = null;
			}
			if (pos != null) {
				final String fineToken = getCanonicalForm(lemmas + "." + pos);
				if (graph.getFineMap().containsKey(fineToken)) return graph.getFineMap().get(fineToken);
			}
			final String coarseToken = getCanonicalForm(lemmas);
			if (coarseMap.containsKey(coarseToken)) return coarseMap.get(coarseToken);
		}
		return mFrameMap.keySet();
	}

	public String getBestFrame(String frameLine, String parseLine)
	{
		String result = null;
		double maxVal = -Double.MIN_VALUE;
		String[] toks = frameLine.split("\t");
		String[] tokNums = toks[1].split("_");
		int[] intTokNums = new int[tokNums.length];
		for(int j = 0; j < tokNums.length; j ++)
			intTokNums[j] = new Integer(tokNums[j]);
		Arrays.sort(intTokNums);
		StringTokenizer st = new StringTokenizer(parseLine,"\t");
		int tokensInFirstSent = new Integer(st.nextToken());
		String[][] data = new String[6][tokensInFirstSent];
		for(int k = 0; k < 6; k ++)
		{
			data[k]=new String[tokensInFirstSent];
			for(int j = 0; j < tokensInFirstSent; j ++)
			{
				data[k][j]=""+st.nextToken().trim();
			}
		}	
		Set<String> set = checkPresenceOfTokensInMap(intTokNums,data);
		if(set==null)
		{
			set = mFrameMap.keySet();
			System.out.println("Notfound:\t"+frameLine);
		}
		for(String frame: set)
		{
			double val =  getNumeratorValue(frame, intTokNums, data);
			if(val>maxVal)
			{
				maxVal = val;
				result=""+frame;
			}
		}
		return result;
	}
}


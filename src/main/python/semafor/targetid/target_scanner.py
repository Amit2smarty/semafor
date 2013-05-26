'''
Scan a frame-annotated file and create a dictionary of its targets 
and a dictionary of all unigrams. Dictionary entries are lemmatized 
and POS-tagged, with counts.

@author: Nathan Schneider (nschneid@cs.cmu.edu)
@since: 2013-05-25
'''
from __future__ import print_function, division
import os, sys, json, codecs
from collections import Counter

from semafor.formats.wordnet import get_lemma

def build_target_dicts(targetdictFP, unifreqFP, dataFP='../scoring/cv.train.sentences.json'):
    uniFreq = Counter()
    targets = Counter()
    with codecs.open(dataFP, 'r', 'utf-8') as inF:
        for ln in inF:
            sentence = json.loads(ln)
            
            # unigrams
            #print(sentence['tokens'],sentence['pos'], file=sys.stderr)
            if len(sentence['tokens'])!=len(sentence['pos']):
                # sometimes the data is buggy and missing a POS tag :( 
                # stopgap: insert ? as the tag
                # TODO: fix this in preprocessing, make an assert here
                posmap = {stuff['start']: stuff for stuff in sentence['pos']}
                sentence['pos'] = [posmap.get(i, {'start': i, 'end': i+1, 'name': '?', 'text': sentence['tokens'][i]}) for i in range(len(sentence['tokens']))]
            
            uniFreq.update(get_lemma(entry['text'], entry['name'])+'_'+entry['name'].upper()[0] for entry in sentence['pos'])
            tokenOffsets = set(range(len(sentence['tokens'])))
            
            # targets
            for frame in sentence['frames']:
                target_toks = {i for span in frame['target']['spans'] for i in range(span['start'],span['end'])}
                tokenOffsets -= target_toks
                lemmas = [get_lemma(entry['text'], entry['name'])+'_'+entry['name'].upper()[0] for i in sorted(target_toks) for entry in [sentence['pos'][i]]]
                targets[' '.join(lemmas)] += 1
                
            # subtract from unigram counts tokens neither part of any target  
            # nor marked as excluded in the word sense layer
            for wslentry in sentence['wsl']:
                tokenOffsets -= {i for i in range(wslentry['start'],wslentry['end'])}
            for i in tokenOffsets:
                remaining_entry = sentence['pos'][i]
                w, pos = remaining_entry['text'], remaining_entry['name']
                remaining_lemma = get_lemma(w, pos)+'_'+pos.upper()[0]
                uniFreq[remaining_lemma] -= 1
    
    with codecs.open(unifreqFP, 'w', 'utf-8') as outF:
        for w,n in uniFreq.items():
            outF.write(w+'\t'+str(n)+'\n')
    with codecs.open(targetdictFP, 'w', 'utf-8') as outF:
        for ww,n in targets.items():
            outF.write(ww+'\t'+str(n)+'\n')

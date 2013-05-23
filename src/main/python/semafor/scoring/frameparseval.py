#!/usr/bin/env python2.7
"""
Scorer for frame-semantic parsing output.
Requires two JSON files: the gold analysis, and the predictions.

If k-best argument predictions are present, only considers the top-ranked set.

@author: Nathan Schneider (nschneid)
@since: 2013-05-15
"""
from __future__ import print_function, division
import sys, json, codecs, itertools
from pandas import DataFrame

DATE_NAMES = [
    'monday',
    'tuesday',
    'wednesday',
    'thursday',
    'friday',
    'saturday',
    'sunday',
    'january',
    'february',
    'march',
    'april',
    'may',
    'june',
    'july',
    'august',
    'september',
    'october',
    'november',
    'december'
]
# add all three-letter abbreviations
DATE_NAMES += [v for d in DATE_NAMES for v in [d[:3], d[:3]+'.']]


class PRCounter(object):
    COLUMNS = ['Numer', 'PDenom', 'RDenom', 'P', 'R', 'F', 'T', 'N', 'Acc']

    def __init__(self):
        self._df = DataFrame(columns=PRCounter.COLUMNS)

    def __setitem__(self, k, v):
        if isinstance(v[0], int):
            N, gold_set, pred_set = v
        else:
            gold_set, pred_set = v
            N = ''
        entry = {
            'Numer': len(gold_set & pred_set),
            'PDenom': len(pred_set),
            'RDenom': len(gold_set),
            'N': N
        }
        entry['P'] = entry['Numer'] / entry['PDenom'] if entry['PDenom'] else float('nan')
        entry['R'] = entry['Numer'] / entry['RDenom'] if entry['RDenom'] else float('nan')
        entry['F'] = 2 * entry['P'] * entry['R'] / (entry['P'] + entry['R']) if (entry['P'] + entry['R']) else float('nan')
        entry['T'] = None if N == '' else N - len(gold_set ^ pred_set) / 2
        entry['Acc'] = {'': None, 0: float('nan')}[N] if not N else entry['T'] / N
        df = DataFrame.from_items([(e, {k: entry[e]}) for e in PRCounter.COLUMNS])
        self._df = self._df.append(df)

    def __str__(self):
        return str(self._df)

    def __add__(self, that):
        new_df = self._df + that._df
        # counts get added, now we need to recompute ratios
        new_df['P'] = new_df['Numer'] / new_df['PDenom']
        new_df['R'] = new_df['Numer'] / new_df['RDenom']
        new_df['F'] = 2 * new_df['P'] * new_df['R'] / (new_df['P'] + new_df['R'])
        new_df['Acc'] = new_df['T'] / new_df['N']
        result = PRCounter()
        result._df = new_df
        return result

    def to_string(self, *args, **kwargs):
        return self._df.to_string(*args, **kwargs)

    def to_html(self, *args, **kwargs):
        return self._df.to_html(*args, **kwargs)

    def to_csv(self, *args, **kwargs):
        return self._df.to_csv(*args, **kwargs)


class Span(object):
    """
    Encodes one or more ranges of indices, and provides for
    iteration over those indices.

    >>> s = Span(3,6)
    >>> 5 in s
    True
    >>> list(s)
    [3, 4, 5]
    >>> s.encompasses(Span(3,4))
    True
    >>> s + Span(0,3)
    Span(0,6)
    >>> list(Span(3,6, 9,12))
    [3, 4, 5, 9, 10, 11]
    >>> Span(3,6, 9,12).subspans()
    [Span(3,6), Span(9,12)]

    Unlike slice objects, Span objects are hashable, so they
    can be used as dict keys/set entries.
    """
    def __init__(self, *args):
        if not args or len(args)%2!=0:
            raise Exception('Span() constructor must have a positive even number of arguments: '+repr(args))
        self._s = []
        first = True
        for start,stop in sorted(zip(args[::2],args[1::2])):
            if first:
                first = False
            elif self.overlaps(Span(start,stop)):
                raise Exception('Span() constructor must not contain overlapping ranges: '+repr(args))
            elif self._s[-1].stop==start:   # join adjacent subranges
                if self._s[-1].stop==start:
                    self._s[-1] = slice(self._s[-1].start,stop)
                continue
            self._s.append(slice(start,stop))

    def __add__(self, that):
        if len(self._s)!=1 or len(that._s)!=1: assert False
        if self._s[0].stop==that._s[0].start: return Span(self._s[0].start,that._s[0].stop)
        elif that._s[0].stop==self._s[0].start: return Span(that._s[0].start,self._s[0].stop)
        raise Exception('Cannot add non-adjacent spans: '+repr(self)+' + ' + repr(that))
        #TODO: allow adding so as to produce multiple spans

    def __lt__(self, that):
        return self.minstart<that.minstart or self.maxstop<that.maxstop

    def __eq__(self, that):
        return self._s==that._s

    def __hash__(self):
        return sum(17*s.stop+s.start for s in self._s)

    def __repr__(self):
        return 'Span({})'.format(', '.join('{},{}'.format(s.start,s.stop) for s in self._s))

    def __iter__(self):
        return itertools.chain(*[iter(range(s.start,s.stop)) for s in self._s])

    def __len__(self):
        return len(set(self))
    
    def __call__(self, sequence, typ=list or str):
        if typ in (str,unicode):
            return typ(' '.join(sequence[i] for i in self))
        return typ(sequence[i] for i in self)

    def encompasses(self, that):
        return set(that)<=set(self)

    def overlaps_partially(self, that):
        return bool(set(that)-set(self) and set(self)-set(that))

    def overlaps(self,that):
        return bool(set(that)&set(self))

    def contiguous(self):
        return self.maxstop-self.minstart==len(self)
    
    def subspans(self):
        return [Span(s.start,s.stop) for s in self._s]

    @property
    def minstart(self):
        return min(s.start for s in self._s)

    @property
    def maxstop(self):
        return max(s.stop for s in self._s)


def span_from_element(element):
    return Span(*[i for sp in element['spans'] for i in [sp['start'], sp['end']]])


def get_predictions_by_span(frames):
    target_coverage = set()
    frame_names = {}
    arguments = {}
    for frame in frames:
        target_span = span_from_element(frame['target'])
        target_coverage |= set(target_span)
        frame_names[target_span] = frame['target']['name']
        # ignore all but the top-ranked set of argument predictions
        arguments[target_span] = {
            span_from_element(fe): fe['name']
            for fe in frame['annotationSets'][0]['frameElements']
        }
    return target_coverage, frame_names, arguments


def get_non_targets(gold_sentence):
    # index by span
    wsl = {
        Span(entry['start'], entry['end']): (entry['name'].lower(), entry['text'])
        for entry in gold_sentence['wsl']
    }
    ner = {
        Span(entry['start'], entry['end']): (entry['name'].lower(), entry['text'])
        for entry in gold_sentence['ner']
    }
    # "WEA" (weapons) and "DATE" (dates) are the only named entity types that evoke frames
    excluded_ner_spans = {
        sp for sp, (ner_type, text) in ner.items()
        if ner_type != 'wea' and not (ner_type == 'date' and text.lower() in DATE_NAMES)
    }
    excluded_spans = set(wsl.keys()) | excluded_ner_spans
    return wsl, excluded_spans


def score_sentence(gold, predicted):
    assert len(gold['tokens']) == len(predicted['tokens']) and ' '.join(gold['tokens']) == ' '.join(predicted['tokens'])
    sentence_stats = PRCounter()
    num_tokens = len(gold['tokens'])
    pred_target_coverage, pred_frames, pred_args = get_predictions_by_span(predicted['frames'])
    # tokens that should in principle be part of a target
    gold_target_coverage = set(range(num_tokens))
    # target spans with gold frame annotations, plus other tokens that should in principle belong to a target
    gold_target_spans = set()
    # tokens in target spans with gold frame annotations
    gold_frame_target_coverage = set()

    wsl, excluded_spans = get_non_targets(gold)

    gold_frames = {}
    gold_args = {}
    for frame in gold['frames']:
        target = frame['target']
        target_span = span_from_element(target)
        gold_target_spans.add(target_span)
        gold_frame_target_coverage |= set(target_span)
        gold_frames[target_span] = target['name']
        gold_args[target_span] = {
            span_from_element(elt): elt['name']
            for elt in frame['annotationSets'][0]['frameElements']
        }
        if target_span in excluded_spans:
            if target_span in wsl:  # obviously a bug with WSL
                print('WSL bug:', target_span, repr(target_span(gold['tokens'], str)), file=sys.stderr)
                excluded_spans.remove(target_span)
            else:
                print(target['spans'][0]['text'],
                      {
                          entry['name'] for entry in gold['ner']
                          if entry['start'] == target_span.minstart
                      },
                      file=sys.stderr)
        elif len(target_span) > 1:
            for sp in excluded_spans:
                if target_span.overlaps(sp):
                    print('Target span',target_span,repr(target_span(gold['tokens'],str)),'overlaps with excluded span',sp,repr(sp(gold['tokens'],str)), file=sys.stderr)

    for span in excluded_spans:
        gold_target_coverage -= set(span)
    for tkn in gold_target_coverage:
        if tkn not in gold_frame_target_coverage:
            gold_target_spans.add(Span(tkn, tkn + 1))
    sentence_stats['Targets by token'] = num_tokens, gold_target_coverage, pred_target_coverage
    sentence_stats['Targets by span'] = gold_target_spans, set(pred_frames.keys())

    correct_target_spans = set(gold_frames.keys()) & set(pred_frames.keys())
    sentence_stats['Frames with correct targets (ignore P)'] = len(correct_target_spans), set(gold_frames.items()), set(pred_frames.items())

    for span in gold_frames.keys():
        if span not in correct_target_spans:
            del gold_frames[span]
    for span in pred_frames.keys():
        if span not in correct_target_spans:
            del pred_frames[span]
    assert len(gold_frames)==len(pred_frames)==len(correct_target_spans),(gold_frames,pred_frames,correct_target_spans)
    sentence_stats['Frames (correct targets only)'] = len(correct_target_spans), set(gold_frames.items()), set(pred_frames.items())

    all_gold_args = set((tspan,arg) for tspan, args in gold_args.items() for arg in args)
    all_pred_args = set((tspan,arg) for tspan, args in pred_args.items() for arg in args)
    sentence_stats['Argument spans with correct targets'] = all_gold_args, all_pred_args

    all_gold_args = set((tspan,)+arg for tspan, args in gold_args.items() for arg in args.items())
    all_pred_args = set((tspan,)+arg for tspan, args in pred_args.items() for arg in args.items())
    sentence_stats['Arguments, labeled, with correct targets'] = all_gold_args, all_pred_args

    for span in gold_args.keys():
        if span not in correct_target_spans:
            del gold_args[span]
    for span in pred_args.keys():
        if span not in correct_target_spans:
            del pred_args[span]
    all_gold_args = set((tspan,arg) for tspan, args in gold_args.items() for arg in args)
    all_pred_args = set((tspan,arg) for tspan, args in pred_args.items() for arg in args)
    sentence_stats['Argument spans (correct targets only)'] = all_gold_args, all_pred_args

    all_gold_args = set((tspan,)+arg for tspan, args in gold_args.items() for arg in args.items())
    all_pred_args = set((tspan,)+arg for tspan, args in pred_args.items() for arg in args.items())
    sentence_stats['Arguments, labeled (correct targets only)'] = all_gold_args, all_pred_args
    
    # TODO: provided the target is correct, arguments can get credit regardless of the predicted frame label
    # should this be changed/made an option?
    return sentence_stats


if __name__=='__main__':
    gold_filename, pred_filename = sys.argv[1:]
    scores = None
    with codecs.open(gold_filename, 'r', 'utf-8') as gold_file, codecs.open(pred_filename, 'r', 'utf-8') as pred_file:
        for gold_line, pred_line in zip(gold_file, pred_file):
            sent_scores = score_sentence(json.loads(gold_line), json.loads(pred_line))
            if scores is None:
                scores = sent_scores
            else:
                scores = scores + sent_scores
    print(scores.to_string())
    
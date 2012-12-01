#!/usr/bin/env python
from collections import namedtuple
import sys

MaltToken = namedtuple('MaltToken', 'form postag head deprel')


def read_malt(line):
    # last field is some useless number
    line = line.strip().split()[:-1]
    # word/pos/idx_of_parent/dep_label
    tokens = [MaltToken(*word_pos_parent_label.split(u'/'))
              for word_pos_parent_label in line]
    return tokens

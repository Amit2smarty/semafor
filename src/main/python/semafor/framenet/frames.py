#!/usr/bin/env Python2.7
"""
Utilities for walking the frame hierarchy
"""
import sys
from xml.dom.minidom import parse

import networkx as nx
import pandas as pd

from semafor.settings import FRAMES_FILENAME, FRAME_RELATIONS_FILENAME

INF = float('inf')


class RelationTypes(object):
    """ Represents types of relationships between objects in FrameNet """
    # Frame-Frame/FE-FE relations
    PERSPECTIVE_ON = u'Perspective_on'
    USING = u'Using'
    SEE_ALSO = u'See_also'
    INHERITANCE = u'Inheritance'
    PRECEDES = u'Precedes'
    CAUSATIVE_OF = u'Causative_of'
    REFRAMING_MAPPING = u'ReFraming_Mapping'
    INCHOATIVE_OF = u'Inchoative_of'
    SUBFRAME = u'Subframe'
    # Frame-FE relations
    FRAME_ELEMENT = 'frame_element'


class FrameElement(object):
    def __init__(self, fe_id, name, core_type):
        self.id = fe_id
        self.name = name
        self.core_type = core_type

    @property
    def key(self):
        return FrameElement.key_for(self.id)

    @staticmethod
    def key_for(fe_id):
        return "fe:%s" % fe_id

    def __repr__(self):
        return "FrameElement(%s)" % self.name


class Frame(object):
    def __init__(self, frame_id, name, frame_elements):
        self.id = frame_id
        self.name = name
        self.frame_elements = frame_elements

    @property
    def core_fes(self):
        return [fe for fe in self.frame_elements if fe.core_type == "Core"]

    @property
    def non_core_fes(self):
        return [fe for fe in self.frame_elements if fe.core_type != "Core"]

    @property
    def key(self):
        return Frame.key_for(self.id)

    @staticmethod
    def key_for(frame_id):
        return "frame:%s" % frame_id

    def __repr__(self):
        return "Frame(%s, frame_elements=%s)" % \
               (self.name, [fe.name for fe in self.frame_elements])


def fe_from_dom_node(node):
    fe_id = node.attributes["ID"].nodeValue
    name = node.attributes["name"].nodeValue
    core_type = node.attributes["coreType"].nodeValue
    return FrameElement(fe_id, name, core_type)


def frame_from_dom_node(node):
    frame_id = node.attributes["ID"].nodeValue
    name = node.attributes["name"].nodeValue
    fe_nodes = node.getElementsByTagName("fe")
    fes = [fe_from_dom_node(fe) for fe in fe_nodes]
    return Frame(frame_id, name, fes)


class FrameHierarchy(object):
    """ Represents the FrameNet hierarchy """
    PARTIAL_CREDIT_PER_HOP = .8

    def __init__(self, graph):
        self._full_graph = graph
        self._frames = {
            n['obj'].name: n['obj']
            for i, n in graph.nodes(data=True)
            if n['type'] == 'frame'
        }
        self._frame_graph = nx.subgraph(graph, [f.key for f in self._frames.values()])
        self._distances = None

    def parents(self, n, relation_type=RelationTypes.INHERITANCE):
        """ Filter by relation_type """
        if isinstance(n, (FrameElement, Frame)):
            n = n.key
        parents = [self._full_graph.node[par]['obj']
                   for par, child, data in self._full_graph.in_edges(n, data=True)
                   if data['relation_type'] == relation_type]
        return parents

    def children(self, n, relation_type=RelationTypes.INHERITANCE):
        """ Filter by relation_type """
        if isinstance(n, (FrameElement, Frame)):
            n = n.key
        children = [self._full_graph.node[child]['obj']
                    for par, child, data in self._full_graph.out_edges(n, data=True)
                    if data['relation_type'] == relation_type]
        return children

    def get_ancestor(self, obj, relation_type=RelationTypes.INHERITANCE):
        parents = self.parents(obj, relation_type=relation_type)
        while parents:
            if len(parents) > 1:
                print("multiple parents!: %s" % parents)
            obj = parents[0]
            parents = self.parents(obj, relation_type=relation_type)
        return obj

    # TODO(smt): memoizing decorator would be nicer
    def _get_all_distances(self):
        if not self._distances is None:
            return self._distances
        nodes = self._frame_graph.nodes(data=True)
        frame_ids, frame_names = zip(*[(i, n['obj'].name) for i, n in nodes])
        # calculate all pairwise distances
        np_distances = nx.floyd_warshall_numpy(self._frame_graph,
                                               nodelist=frame_ids)
        # pack up into a nice DataFrame keyed on frame.name
        self._distances = pd.DataFrame(np_distances,
                                       index=frame_names,
                                       columns=frame_names)
        return self._distances

    def cost(self, a, b):
        num_hops = self._get_all_distances().get(a.name, {}).get(b.name, INF)
        return 1 - (FrameHierarchy.PARTIAL_CREDIT_PER_HOP ** num_hops)

    @staticmethod
    def load(frames_filename=FRAMES_FILENAME,
             frame_relations_filename=FRAME_RELATIONS_FILENAME):
        frames_dom = parse(frames_filename)
        frames = [frame_from_dom_node(node)
                  for node in frames_dom.getElementsByTagName("frame")]
        graph = nx.MultiDiGraph()
        for frame in frames:
            frame_key = Frame.key_for(frame.id)
            graph.add_node(frame_key, type="frame", obj=frame)
            for fe in frame.frame_elements:
                graph.add_node(fe.key, type="fe", obj=fe)
                graph.add_edge(frame_key, fe.key, relation_type="frame_element")

        frame_relations_dom = parse(frame_relations_filename)
        relation_types = frame_relations_dom.getElementsByTagName("frame-relation-type")
        for relation_type_node in relation_types:
            relation_type = relation_type_node.attributes["name"].nodeValue
            frame_relations = relation_type_node.getElementsByTagName("frame-relation")
            for frame_relation in frame_relations:
                parent_id = frame_relation.attributes["supID"].nodeValue
                child_id = frame_relation.attributes["subID"].nodeValue
                graph.add_edge(Frame.key_for(parent_id),
                               Frame.key_for(child_id),
                               relation_type=relation_type)
                fe_relations = frame_relation.getElementsByTagName("fe-relation")
                for fe_relation in fe_relations:
                    parent_id = fe_relation.attributes["supID"].nodeValue
                    child_id = fe_relation.attributes["subID"].nodeValue
                    graph.add_edge(FrameElement.key_for(parent_id),
                                   FrameElement.key_for(child_id),
                                   relation_type=relation_type)
        return FrameHierarchy(graph)


if "__main__" == __name__:
    # cache all pairwise frame distances
    frame_distance_cache_filename = sys.argv[1]
    hierarchy = FrameHierarchy.load()
    distances = hierarchy._get_all_distances()
    distances.to_csv(frame_distance_cache_filename)

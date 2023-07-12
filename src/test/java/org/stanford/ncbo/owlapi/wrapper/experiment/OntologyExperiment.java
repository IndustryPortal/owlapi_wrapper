package org.stanford.ncbo.owlapi.wrapper.experiment;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.stanford.ncbo.owlapi.wrapper.util.OntologyBasedGraph;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class OntologyExperiment {

    public static void main(String[] args) throws OWLOntologyCreationException {

        try {
            InetAddress id = InetAddress.getLocalHost();
            if (id.toString().contains("enit")) {

                // Set proxy (enit) properties using System.setProperty
                System.setProperty("http.proxyHost", "squid02.local.enit.fr");
                System.setProperty("http.proxyPort", "3128");
                System.setProperty("https.proxyHost", "squid02.local.enit.fr");
                System.setProperty("https.proxyPort", "3128");
                System.out.println("set properties enit proxy");
            }
        } catch (UnknownHostException e) {
            System.out.println("error in getting hostname");
        }

        String path = "src/test/resources/BRO_v3.4.owl";
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new File(path));
        OntologyBasedGraph graph = OntologyBasedGraph.getGraph(ontology);
        long start = System.currentTimeMillis();
        System.out.println("depth = " + graph.maxDepth());
        System.out.println("Took " + (start-System.currentTimeMillis()) + " ms");
    }
}

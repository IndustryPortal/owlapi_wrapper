package org.stanford.ncbo.oapiwrapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.AutoIRIMapper;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.OWLEntityRemover;
import org.semanticweb.owlapi.util.SimpleIRIMapper;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import com.google.common.base.Optional;

public class OntologyParser {
	protected ParserInvocation parserInvocation = null;
	private List<OntologyBean> ontologies = new ArrayList<OntologyBean>();
	private OWLOntologyManager sourceOwlManager = null;
	private OWLOntologyManager targetOwlManager = null;
	private OWLOntology targetOwlOntology = null;
	private OWLOntology localMaster = null;

	public List<OntologyBean> getLocalOntologies() {
		return ontologies;
	}

	private final static Logger log = Logger.getLogger(OntologyParser.class
			.getName());

	private void addBFOLocationMapping(OWLOntologyManager m) {
		SimpleIRIMapper bfoCached = new SimpleIRIMapper(
				IRI.create("http://www.ifomis.org/bfo/1.1"),
				IRI.create("https://raw.githubusercontent.com/ncbo/bfo/master/bfo-1.1.owl"));
		m.addIRIMapper(bfoCached);
	}

	private void setLocalFileRepositaryMapping(OWLOntologyManager m,
			String folder) {
		if (this.parserInvocation.getInputRepositoryFolder() != null) {
			File rooDirectory = new File(folder);
			m.addIRIMapper(new AutoIRIMapper(rooDirectory, true));
		}
	}


	public OntologyParser(ParserInvocation parserInvocation)
			throws OntologyParserException {
		super();
		log.info("executor ...");
		this.parserInvocation = parserInvocation;
		if (!parserInvocation.valid())
			throw new OntologyParserException(
					this.parserInvocation.getParserLog());

		this.sourceOwlManager = OWLManager.createOWLOntologyManager();

		/* the second manager does not have the bogus obo parser */
		this.sourceOwlManager = OWLManager.createOWLOntologyManager();

		this.setLocalFileRepositaryMapping(this.sourceOwlManager,
				this.parserInvocation.getInputRepositoryFolder());
		this.addBFOLocationMapping(this.sourceOwlManager);

		this.targetOwlManager = OWLManager.createOWLOntologyManager();
	}

	public String getOBODataVersion(String file) {
		String result = null;
		String line = null;
		try {
			BufferedReader reader = new BufferedReader(new FileReader(file));
			while ((line = reader.readLine()) != null) {
				if (line.contains("data-version:")) {
					String[] version = line.split(" ");
					if (version.length > 1) {
						reader.close();
						return version[1];
					}
				}
			}
			reader.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	private void findLocalOntologies() {
		String oboVersion = null;
		if (parserInvocation.getInputRepositoryFolder() != null) {
			log.info("[" + parserInvocation.getInvocationId()
					+ "] findLocalOntologies in "
					+ parserInvocation.getInputRepositoryFolder());
			File repo = new File(parserInvocation.getInputRepositoryFolder());
			if (repo.isDirectory()) {
				@SuppressWarnings("unchecked")
				Iterator<File> files = FileUtils.iterateFiles(repo,
						new OntologySuffixFileFilter(), new DirectoryFilter());
				ontologies = new ArrayList<OntologyBean>();
				while (files.hasNext()) {
					File f = files.next();
					if (f.getAbsolutePath().toLowerCase().endsWith("obo")) {
						oboVersion = getOBODataVersion(f.getAbsolutePath());
					}
					ontologies.add(new OntologyBean(f));
					log.info("[" + parserInvocation.getInvocationId()
							+ "] findLocalOntologies in " + f.getName());
				}
			}
		} else {
			if (this.parserInvocation.getMasterFileName().toLowerCase()
					.endsWith("obo")) {
				oboVersion = getOBODataVersion(this.parserInvocation
						.getMasterFileName());
			}
			this.ontologies.add(new OntologyBean(new File(this.parserInvocation
					.getMasterFileName())));
			log.info("getInputRepositoryFolder is not provided. Unique file being parse.");
		}
		if (oboVersion != null) {
			parserInvocation.setOBOVersion(oboVersion);
		}
	}

	private boolean isOBO() {
		boolean isOBO = false;
		for (OWLOntology sourceOnt : this.sourceOwlManager.getOntologies()) {
			OWLDocumentFormat format = this.sourceOwlManager
					.getOntologyFormat(sourceOnt);
			isOBO = isOBO || (format instanceof OBODocumentFormat);
			System.out.println("@@Format " + format.getClass().getName());
		}
		return isOBO;
	}

	private void addGroundMetadata(IRI documentIRI, OWLDataFactory fact,
			OWLOntology sourceOnt) {
		if (!sourceOnt.getOntologyID().isAnonymous()) {
			for (OWLAnnotation ann : sourceOnt.getAnnotations()) {
				Optional<IRI> sub = sourceOnt.getOntologyID().getOntologyIRI();
				IRI iriSub = sub.get();
				OWLAnnotationAssertionAxiom groundAnnotation = fact
						.getOWLAnnotationAssertionAxiom(ann.getProperty(),
								iriSub,
								ann.getValue());
				this.targetOwlManager.addAxiom(targetOwlOntology,
						groundAnnotation);
				if (documentIRI.toString().startsWith("file:/")) {
					if (ann.getProperty().toString().contains("versionInfo")) {
						OWLAnnotationProperty prop = fact
								.getOWLAnnotationProperty(IRI
										.create(OWLRDFVocabulary.OWL_VERSION_INFO
												.toString()));
						OWLAnnotationAssertionAxiom annVersion = fact
								.getOWLAnnotationAssertionAxiom(
										prop,
										IRI.create("http://bioportal.bioontology.org/ontologies/versionSubject"),
										ann.getValue());
						this.targetOwlManager.addAxiom(targetOwlOntology,
								annVersion);
					}
				}
			}
		}
	}

	private void addOntologyIRI(OWLDataFactory fact, OWLOntology sourceOnt) {
		// Add a triple with the ontology URI to the submission graph
		// <http://bioportal.bioontology.org/ontologies/versionSubject> <http://omv.ontoware.org/2005/05/ontology#URI> "ONTOLOGY_IRI"
		if (!sourceOnt.getOntologyID().isAnonymous()) {
			Optional<IRI> sub = sourceOnt.getOntologyID().getOntologyIRI();
			IRI ontologyIRI = sub.get();
			OWLAnnotationProperty prop = fact
					.getOWLAnnotationProperty(IRI
							.create(OWLRDFVocabulary.OWL_VERSION_INFO.toString()));
			OWLAnnotationAssertionAxiom annOntoURI = fact
					.getOWLAnnotationAssertionAxiom(prop,
							IRI.create("http://bioportal.bioontology.org/ontologies/URI"),
							fact.getOWLLiteral(ontologyIRI.toString()));
			this.targetOwlManager.addAxiom(targetOwlOntology,
					annOntoURI);
		}
	}

	private boolean buildOWLOntology() {

		Set<OWLAxiom> allAxioms = new HashSet<OWLAxiom>();
		boolean isOBO = false;

		OWLDataFactory fact = sourceOwlManager.getOWLDataFactory();
		try {
			this.targetOwlOntology = targetOwlManager.createOntology();
                        addOntologyIRI(sourceOwlManager.getOWLDataFactory(), this.localMaster);
		} catch (OWLOntologyCreationException e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			StringWriter trace = new StringWriter();
			e.printStackTrace(new PrintWriter(trace));
			parserInvocation.getParserLog().addError(
					ParserError.OWL_CREATE_ONTOLOGY_EXCEPTION,
					"Error buildOWLOntology" + e.getMessage() + "\n"
							+ trace.toString());
			log.info(e.getMessage());
			return false;
		}

		isOBO = this.isOBO();

		Set<OWLClass> toDelete = new HashSet<OWLClass>();
		for (OWLOntology sourceOnt : this.sourceOwlManager.getOntologies()) {
			IRI documentIRI = this.sourceOwlManager
					.getOntologyDocumentIRI(sourceOnt);
			System.out.println("ontology inspect " + documentIRI.toString());
			addGroundMetadata(documentIRI, fact, sourceOnt);

			generateGroundTriplesForAxioms(allAxioms, fact, sourceOnt);

			if (isOBO) {
				if (!documentIRI.toString().startsWith("owlapi:ontology"))
					toDelete.addAll( generateSKOSInObo(allAxioms, fact, sourceOnt) );
			}

			boolean isPrefixedOWL = this.sourceOwlManager.getOntologyFormat(
					sourceOnt).isPrefixOWLOntologyFormat();
			System.out.println("isPrefixOWLOntologyFormat " + isPrefixedOWL);
			if (isPrefixedOWL == true && !isOBO) {
				generateSKOSInOwl(allAxioms, fact, sourceOnt);
			}
		}

		targetOwlManager.addAxioms(this.targetOwlOntology, allAxioms);
		for (OWLAnnotation ann : this.targetOwlOntology.getAnnotations()) {
			AddOntologyAnnotation addAnn = new AddOntologyAnnotation(
					this.targetOwlOntology, ann);
			targetOwlManager.applyChange(addAnn);
		}

		if (isOBO) {
			OWLEntityRemover rem = new OWLEntityRemover(Collections.singleton(this.targetOwlOntology));
			for (OWLClass cls : toDelete) {
					cls.accept(rem);
			}
			this.targetOwlOntology.getOWLOntologyManager().applyChanges(rem.getChanges());

			if (parserInvocation.getOBOVersion() != null) {
				System.out.println("@@ adding version "
						+ parserInvocation.getOBOVersion());
				OWLAnnotationProperty prop = fact.getOWLAnnotationProperty(IRI
						.create(OWLRDFVocabulary.OWL_VERSION_INFO.toString()));
				OWLAnnotationAssertionAxiom annVersion = fact
						.getOWLAnnotationAssertionAxiom(
								prop,
								IRI.create("http://bioportal.bioontology.org/ontologies/versionSubject"),
								fact.getOWLLiteral(parserInvocation
										.getOBOVersion()));
				targetOwlManager.addAxiom(targetOwlOntology, annVersion);
			}
		}

		for (OWLOntology sourceOnt : this.sourceOwlManager.getOntologies()) {
			for (OWLAnnotation ann : sourceOnt.getAnnotations()) {
				AddOntologyAnnotation addAnn = new AddOntologyAnnotation(
						this.targetOwlOntology, ann);
				targetOwlManager.applyChange(addAnn);
			}
		}

		escapeXMLLiterals(targetOwlOntology);

		OWLReasonerFactory reasonerFactory = null;
		OWLReasoner reasoner = null;
		reasonerFactory = new StructuralReasonerFactory();
		reasoner = reasonerFactory.createReasoner(this.targetOwlOntology);
		InferredSubClassAxiomGenerator isc = new InferredSubClassAxiomGenerator();
		Set<OWLSubClassOfAxiom> subAxs = isc.createAxioms(
				this.targetOwlOntology.getOWLOntologyManager().getOWLDataFactory(), reasoner);
		targetOwlManager.addAxioms(this.targetOwlOntology, subAxs);
		deprecateBranch();


		System.out.println("isOBO " + isOBO);
		if (isOBO) {
			replicateHierarchyAsTreeview(fact);
		}
		return true;
	}

	private void replicateHierarchyAsTreeview(OWLDataFactory fact) {
		Set<OWLAxiom> treeViewAxs = new HashSet<OWLAxiom>();
		for (OWLAxiom axiom : targetOwlOntology.getAxioms()) {
			if (axiom instanceof OWLSubClassOfAxiom) {
				OWLSubClassOfAxiom scAxiom = (OWLSubClassOfAxiom) axiom;
				OWLAnnotationProperty prop = fact
						.getOWLAnnotationProperty(IRI
								.create("http://data.bioontology.org/metadata/treeView"));
				if (!scAxiom.getSubClass().isAnonymous()
						&& !scAxiom.getSuperClass().isAnonymous()) {
					OWLAxiom annAsse = fact.getOWLAnnotationAssertionAxiom(
							prop, scAxiom.getSubClass().asOWLClass().getIRI(),
							scAxiom.getSuperClass().asOWLClass().getIRI());
					treeViewAxs.add(annAsse);
				}
			}
		}
		targetOwlManager.addAxioms(targetOwlOntology, treeViewAxs);
	}

	private void deprecateBranch() {
		Set<OWLEntity> things = targetOwlOntology.getEntitiesInSignature(IRI
				.create("http://www.w3.org/2002/07/owl#Thing"));
		OWLClass thing = null;
		for (OWLEntity t : things) {
			thing = (OWLClass) t;
		}
		Set<OWLSubClassOfAxiom> rootsEdges = targetOwlOntology
				.getSubClassAxiomsForSuperClass(thing);
		for (OWLSubClassOfAxiom rootEdge : rootsEdges) {
			if (!rootEdge.getSubClass().isAnonymous()) {
				OWLClass subClass = (OWLClass) rootEdge.getSubClass();
				String rootID = subClass.getIRI().toString();
				if (rootID.toLowerCase().contains("obo")) {
					Collection<OWLAnnotation> annotationsRoot = EntitySearcher.getAnnotations(subClass, targetOwlOntology);
					boolean hasLabel = false;
					for (OWLAnnotation annRoot : annotationsRoot) {
						hasLabel = hasLabel
								|| annRoot
										.getProperty()
										.toString()
										.equals("http://www.w3.org/2000/01/rdf-schema#label")
								|| annRoot.getProperty().toString()
										.equals("rdfs:label");
						if (annRoot.isDeprecatedIRIAnnotation()) {
							if (annRoot.getValue().toString().contains("true")) {
								RemoveAxiom remove = new RemoveAxiom(
										targetOwlOntology, rootEdge);
								targetOwlManager.applyChange(remove);
							}
						}
					}
					Collection<OWLAnnotationAssertionAxiom> assRoot = EntitySearcher.getAnnotationAssertionAxioms(subClass,targetOwlOntology);
					for (OWLAnnotationAssertionAxiom annRoot : assRoot) {
						if (annRoot.getProperty().toString()
								.contains("treeView")) {
							RemoveAxiom remove = new RemoveAxiom(
									targetOwlOntology, rootEdge);
							targetOwlManager.applyChange(remove);
						}
					}
					if (!hasLabel) {
						RemoveAxiom remove = new RemoveAxiom(targetOwlOntology,
								rootEdge);
						targetOwlManager.applyChange(remove);
					}
				}
			}
		}
	}

	private void generateSKOSInOwl(Set<OWLAxiom> allAxioms,
			OWLDataFactory fact, OWLOntology sourceOnt) {
		OWLDocumentFormat docFormat = this.sourceOwlManager
				.getOntologyFormat(sourceOnt);
		PrefixDocumentFormat prefixFormat = docFormat.asPrefixOWLOntologyFormat();
		Set<OWLClass> classes = sourceOnt.getClassesInSignature();
		for (OWLClass cls : classes) {
			if (!cls.isAnonymous()) {
				boolean notationFound = false;
				for (OWLAnnotation ann : EntitySearcher.getAnnotations(cls, targetOwlOntology)) {
					if (ann.getProperty()
							.toString()
							.contains(
									"http://www.w3.org/2004/02/skos/core#notation")) {
						notationFound = true;
						break;
					}
				}
				if (notationFound) {
					continue;
				}
				for (OWLAnnotation ann : EntitySearcher.getAnnotations(cls, sourceOnt)) {
					if (ann.getProperty()
							.toString()
							.contains(
									"http://www.geneontology.org/formats/oboInOwl#id")) {
						OWLAnnotationProperty prop = fact
								.getOWLAnnotationProperty(IRI
										.create("http://www.w3.org/2004/02/skos/core#notation"));
						OWLAxiom annAsse = fact.getOWLAnnotationAssertionAxiom(
								prop, cls.getIRI(), ann.getValue());
						allAxioms.add(annAsse);
						notationFound = true;
						break;
					}
				}
				if (notationFound) {
					continue;
				}
				String prefixIRI = prefixFormat.getPrefixIRI(cls.getIRI());
				if (prefixIRI != null) {
					if (prefixIRI.startsWith(":")) {
						prefixIRI = prefixIRI.substring(1);
					}
					if (prefixIRI.startsWith("obo:") && prefixIRI.contains("_")) {
						// OBO ontologies transformed into OWL before submitting
						// to BioPortal
						prefixIRI = prefixIRI.substring(4);
						StringBuilder b = new StringBuilder(prefixIRI);
						int ind = prefixIRI.lastIndexOf("_");
						b.replace(ind, ind + 1, ":");
						prefixIRI = b.toString();
					}
					OWLAnnotationProperty prop = fact
							.getOWLAnnotationProperty(IRI
									.create("http://data.bioontology.org/metadata/prefixIRI"));
					OWLAxiom annAsse = fact.getOWLAnnotationAssertionAxiom(
							prop, cls.getIRI(), fact.getOWLLiteral(prefixIRI));
					allAxioms.add(annAsse);
				}
			}
		}
	}

	private Set<OWLClass> generateSKOSInObo(Set<OWLAxiom> allAxioms,
			 OWLDataFactory fact, OWLOntology sourceOnt) {
		Set<OWLClass> classesWithNotation = new HashSet<OWLClass>();
		OWLAnnotationProperty notation = fact
				.getOWLAnnotationProperty(IRI
						.create("http://www.w3.org/2004/02/skos/core#notation"));
		for (OWLAnnotationAssertionAxiom ann : sourceOnt.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
			//set with all and if not there delete
			OWLAnnotationSubject s = ann.getSubject();
			OWLAnnotationProperty p = ann.getProperty();

			if (p.toString().contains("#id")) {
				OWLAxiom annAsse = fact.getOWLAnnotationAssertionAxiom(
						notation, (IRI)s, ann.getValue());
				allAxioms.add(annAsse);
				classesWithNotation.add(fact.getOWLClass((IRI)s));
				
			}
		}

		Set<OWLClass> classesToDelete = new HashSet<OWLClass>();
		Set<OWLClass> classes = sourceOnt.getClassesInSignature();
		for (OWLClass cls : classes) {
			if (!classesWithNotation.contains(cls)) {
				System.out.println("TO DELETE " + cls.getIRI());
				classesToDelete.add(cls);

			}
		}
		return classesToDelete;
	}
	
	private void escapeXMLLiterals(OWLOntology target) {
		OWLDataFactory td = targetOwlManager.getOWLDataFactory();
		Set<OWLClass> classes = target.getClassesInSignature();
		for (OWLClass cls : classes) {
			if (!cls.isAnonymous()) {
				for (OWLAnnotationAssertionAxiom ann : EntitySearcher.getAnnotationAssertionAxioms(cls, target)) {
					for (OWLDatatype t : ann.getValue().getDatatypesInSignature()) {
						if (t.toString().contains("XMLLiteral")) {
							System.out.println("stripping xml " + cls.getIRI().toString() +" "+ ann.getProperty().toString());
							String noXMLString = ann.getValue().asLiteral().get().getLiteral().replaceAll("\\<.*?\\>", "");
							System.out.println(ann.getValue().toString());
							System.out.println(noXMLString);
							OWLAnnotationAssertionAxiom annAsse = td.getOWLAnnotationAssertionAxiom(
									ann.getProperty(), cls.getIRI(), td.getOWLLiteral(noXMLString));
							targetOwlManager.addAxiom(target, annAsse);
							Set<OWLAnnotationAssertionAxiom> del = new HashSet<OWLAnnotationAssertionAxiom>();
							del.add(ann);
							targetOwlManager.removeAxioms(target,del); 
						}
					}
				}
			}
		}
	}


	private void generateGroundTriplesForAxioms(Set<OWLAxiom> allAxioms,
			OWLDataFactory fact, OWLOntology sourceOnt) {
		for (OWLAxiom axiom : sourceOnt.getAxioms()) {
			allAxioms.add(axiom);

			if (axiom instanceof OWLSubClassOfAxiom) {
				OWLSubClassOfAxiom sc = (OWLSubClassOfAxiom) axiom;
				OWLClassExpression ce = sc.getSuperClass();
				try {
					sc.getSubClass().asOWLClass().getIRI();
				} catch (OWLRuntimeException exc) {
					continue;
				}
				if (ce instanceof OWLObjectSomeValuesFrom) {
					OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) ce;
					if (!some.getProperty().isAnonymous()
							&& !some.getFiller().isAnonymous()) {
						String propSome = some.getProperty()
								.asOWLObjectProperty().getIRI().toString()
								.toLowerCase();
						if (propSome.contains("obo")) {
							if (propSome.endsWith("part_of")
									|| propSome.endsWith("bfo_0000050")
									|| propSome.endsWith("contains")
									|| propSome.endsWith("ro_0001019")
									|| propSome.endsWith("develops_from")
									|| propSome.endsWith("ro_0002202")) {
								OWLAnnotationProperty prop = null;
								if (propSome.endsWith("contains")
										|| propSome.endsWith("ro_0001019")) {
									prop = fact
											.getOWLAnnotationProperty(IRI
													.create("http://data.bioontology.org/metadata/obo/contains"));
									OWLAxiom annAsse = fact
											.getOWLAnnotationAssertionAxiom(
													prop, some.getFiller()
															.asOWLClass()
															.getIRI(), sc
															.getSubClass()
															.asOWLClass()
															.getIRI());
									allAxioms.add(annAsse);

									prop = fact
											.getOWLAnnotationProperty(IRI
													.create("http://data.bioontology.org/metadata/treeView"));
									annAsse = fact
											.getOWLAnnotationAssertionAxiom(
													prop, some.getFiller()
															.asOWLClass()
															.getIRI(), sc
															.getSubClass()
															.asOWLClass()
															.getIRI());
									allAxioms.add(annAsse);
								} else {
									if (propSome.endsWith("part_of")
											|| propSome.endsWith("bfo_0000050"))
										prop = fact
												.getOWLAnnotationProperty(IRI
														.create("http://data.bioontology.org/metadata/obo/part_of"));
									else
										prop = fact
												.getOWLAnnotationProperty(IRI
														.create("http://data.bioontology.org/metadata/obo/develops_from"));
									OWLAxiom annAsse = fact
											.getOWLAnnotationAssertionAxiom(
													prop, sc.getSubClass()
															.asOWLClass()
															.getIRI(), some
															.getFiller()
															.asOWLClass()
															.getIRI());
									allAxioms.add(annAsse);

									prop = fact
											.getOWLAnnotationProperty(IRI
													.create("http://data.bioontology.org/metadata/treeView"));
									annAsse = fact
											.getOWLAnnotationAssertionAxiom(
													prop, sc.getSubClass()
															.asOWLClass()
															.getIRI(), some
															.getFiller()
															.asOWLClass()
															.getIRI());
									allAxioms.add(annAsse);
								}
							} else {
								if (!some.getFiller().isAnonymous()
										&& !sc.getSubClass().isAnonymous()) {
									OWLAnnotationProperty prop = fact
											.getOWLAnnotationProperty(some
													.getProperty()
													.asOWLObjectProperty()
													.getIRI());
									OWLAxiom annAsse = fact
											.getOWLAnnotationAssertionAxiom(
													prop, sc.getSubClass()
															.asOWLClass()
															.getIRI(), some
															.getFiller()
															.asOWLClass()
															.getIRI());
									allAxioms.add(annAsse);
								}
							}
						}
					}
				}
			}
		}
	}

        /**
         * Call internal parse to parse the ontology
         * @return
         * @throws Exception 
         */
	public boolean parse() throws Exception {
		try {
			if (internalParse()) {
				parserInvocation.saveErrors();
				return true;
			}
			parserInvocation.saveErrors();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			StringWriter trace = new StringWriter();
			e.printStackTrace(new PrintWriter(trace));
			parserInvocation.getParserLog()
					.addError(
							ParserError.UNKNOWN,
							"Error " + e.getMessage() + "\nTrace:\n"
									+ trace.toString());
		}
		parserInvocation.saveErrors();
		return false;
	}

        /**
         * Main function that is doing the ontology parsing
         * @return boolean
         */
	private boolean internalParse() {
		findLocalOntologies();
		this.localMaster = findMasterFile();
		if (this.localMaster == null) {
			String message = "Error cannot find "
					+ this.parserInvocation.getMasterFileName()
					+ " in input folder.";
			parserInvocation.getParserLog().addError(
					ParserError.MASTER_FILE_MISSING, message);
			log.info(message);
			return false;
		}
		if (buildOWLOntology()) {
			if (serializeOntology()) {
				return true;
			} else {
				return false;
			}
		} else {
			// abort error in parsing
			return false;
		}
	}

	private boolean serializeOntology() {
		log.info("Serializing ontology in RDF ...");
		File output = new File(parserInvocation.getOutputRepositoryFolder()
				+ File.separator + "owlapi.xrdf");
		IRI newPath = IRI.create("file:" + output.getAbsolutePath());
		try {
			this.targetOwlManager.saveOntology(this.targetOwlOntology, new RDFXMLDocumentFormat(),newPath);
		} catch (OWLOntologyStorageException e) {
			log.log(Level.ALL, e.getMessage(), e);
			StringWriter trace = new StringWriter();
			e.printStackTrace(new PrintWriter(trace));
			parserInvocation.getParserLog().addError(
					ParserError.OWL_STORAGE_EXCEPTION,
					"Error buildOWLOntology" + e.getMessage() + "\n"
							+ trace.toString());
			if (output.exists()) {
				output.renameTo(new File(parserInvocation
						.getOutputRepositoryFolder()
						+ File.separator
						+ "owlapi.xrdf.incomplete"));
			}
			return false;
		}
		log.info("Serialization done!");
		return true;
	}

        /**
         * Find the main file defining the ontology (mainly useful when a zip file is uploaded)
         * @return the main OWLOntology file
         */
	private OWLOntology findMasterFile() {

		OWLOntologyLoaderConfiguration conf = new OWLOntologyLoaderConfiguration();
		conf = conf
				.setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
		LogMissingImports missingHandler = new LogMissingImports(
				parserInvocation.getParserLog());
		this.sourceOwlManager.addMissingImportListener(missingHandler);

		if (this.parserInvocation.getInputRepositoryFolder() == null) {
			try {
				return this.sourceOwlManager.loadOntologyFromOntologyDocument(
						new FileDocumentSource(new File(this.parserInvocation
								.getMasterFileName())), conf);
			} catch (OWLOntologyCreationException e) {
				log.log(Level.SEVERE, e.getMessage(), e);
				StringWriter trace = new StringWriter();
				e.printStackTrace(new PrintWriter(trace));
				parserInvocation.getParserLog().addError(
						ParserError.OWL_PARSE_EXCEPTION,
						"Error parsing"
								+ this.parserInvocation.getMasterFileName()
								+ "\n" + e.getMessage() + "\n"
								+ trace.toString());
				log.info(e.getMessage());
				return null;
			}
		}

		// repo input for zip files
		File master = new File(new File(
				parserInvocation.getInputRepositoryFolder()),
				this.parserInvocation.getMasterFileName());
		log.info("---> master.getAbsolutePath(): " + master.getAbsolutePath());

		OntologyBean selectedBean = null;
		for (OntologyBean b : this.ontologies) {
			log.info("---> "
					+ b.getFile().getAbsolutePath()
					+ " --> "
					+ master.getAbsolutePath().equals(
							b.getFile().getAbsolutePath()));
			if (b.getFile().getAbsolutePath().equals(master.getAbsolutePath())) {
				selectedBean = b;
			}
		}
		if (selectedBean == null) {
			for (OntologyBean b : this.ontologies) {
				log.info("---> "
						+ b.getFile().getAbsolutePath()
						+ " --> "
						+ master.getAbsolutePath().equals(
								b.getFile().getAbsolutePath()));
				if (b.getFile().getName()
						.equals(this.parserInvocation.getMasterFileName())) {
					selectedBean = b;
				}
			}
		}

		log.info("Selected master file "
				+ selectedBean.getFile().getAbsolutePath());

		if (selectedBean != null) {
			try {
				return this.sourceOwlManager.loadOntologyFromOntologyDocument(
						new FileDocumentSource(selectedBean.getFile()), conf);
			} catch (OWLOntologyCreationException e) {
				log.log(Level.SEVERE, e.getMessage(), e);
				StringWriter trace = new StringWriter();
				e.printStackTrace(new PrintWriter(trace));
				parserInvocation.getParserLog().addError(
						ParserError.OWL_PARSE_EXCEPTION,
						"Error parsing"
								+ selectedBean.getFile().getAbsolutePath()
								+ "\n" + e.getMessage() + "\n"
								+ trace.toString());
				log.info(e.getMessage());
				return null;
			}
		}
		return null;
	}

	public Set<OWLOntology> getParsedOntologies() {
		return this.sourceOwlManager.getOntologies();
	}

	private static Pattern SEPARATOR_PATTERN = Pattern
			.compile("([^#_|_]+)(#_|_)(.+)");
}

package edu.umd.cs.example;

import edu.umd.cs.psl.application.inference.LazyMPEInference;
import edu.umd.cs.psl.application.learning.weight.maxlikelihood.LazyMaxLikelihoodMPE;
import edu.umd.cs.psl.config.*
import edu.umd.cs.psl.database.DataStore
import edu.umd.cs.psl.database.Database;
import edu.umd.cs.psl.database.Partition;
import edu.umd.cs.psl.database.ReadOnlyDatabase;
import edu.umd.cs.psl.database.rdbms.RDBMSDataStore
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver.Type
import edu.umd.cs.psl.groovy.PSLModel;
import edu.umd.cs.psl.groovy.PredicateConstraint;
import edu.umd.cs.psl.groovy.SetComparison;
import edu.umd.cs.psl.model.argument.ArgumentType;
import edu.umd.cs.psl.model.argument.GroundTerm;
import edu.umd.cs.psl.model.atom.GroundAtom;
import edu.umd.cs.psl.model.function.ExternalFunction;
import edu.umd.cs.psl.ui.functions.textsimilarity.*
import edu.umd.cs.psl.ui.loading.InserterUtils;
import edu.umd.cs.psl.util.database.Queries;

/* 
 * The first thing we need to do is initialize a ConfigBundle and a DataStore
 */

/*
 * A ConfigBundle is a set of key-value pairs containing configuration options. One place these
 * can be defined is in psl-example/src/main/resources/psl.properties
 */
ConfigManager cm = ConfigManager.getManager()
ConfigBundle config = cm.getBundle("basic-example")

/* Uses H2 as a DataStore and stores it in a temp. directory by default */
def defaultPath = System.getProperty("java.io.tmpdir")
String dbpath = config.getString("dbpath", defaultPath + File.separator + "basic-example")
DataStore data = new RDBMSDataStore(new H2DatabaseDriver(Type.Disk, dbpath, true), config)

/*
 * Now we can initialize a PSLModel, which is the core component of PSL.
 * The first constructor argument is the context in which the PSLModel is defined.
 * The second argument is the DataStore we will be using.
 */
PSLModel m = new PSLModel(this, data)

/* 
 * We create three predicates in the model, giving their names and list of argument types
 */
m.add predicate: "domainArg" , types: [ArgumentType.UniqueID, ArgumentType.UniqueID]
m.add predicate: "rangeArg" , types: [ArgumentType.UniqueID, ArgumentType.UniqueID]
m.add predicate: "inferable", types: [ArgumentType.UniqueID, ArgumentType.UniqueID]


/* 
 * Having added all the predicates we need to represent our problem, we finally insert some rules into the model.
 * Rules are defined using a logical syntax. Uppercase letters are variables and the predicates used in the rules below
 * are those defined above. The character '&' denotes a conjunction wheres '>>' denotes a conclusion.
 * Each rule can be given a user defined weight or no weight is specified if it is learned.
 * 
 * 'A ^ B' is a shorthand syntax for nonsymmetric(A,B), which means that in the grounding of the rule,
 * PSL does not ground the symmetric case.
 */

m.add rule : ( inferable(X,Y) & domainArg(A,X) ) >> inferable(A,Y),  weight : 100
m.add rule : ( inferable(X,Y) & rangeArg(A,X) ) >> inferable(A,Y),  weight : 100



m.add PredicateConstraint.PartialFunctional , on : inferable
//m.add PredicateConstraint.PartialInverseFunctional , on : inferable

/*
m.add PredicateConstraint.PartialFunctional , on : samePerson
m.add PredicateConstraint.PartialInverseFunctional , on : samePerson
m.add PredicateConstraint.Symmetric, on : samePerson
*/

/* Prior to suggest that by default nothing is evaluated TRUE -- may create a Bias for questions evaluated by crowd as False */
m.add rule: ~inferable(A,Y), weight: 10

/* Print the model with all rules */
println m;

/* Inserting data from the files into DataStore -- Note the format for columns of input file*/

def dir = 'data'+java.io.File.separator+'sn'+java.io.File.separator;
Partition domainPart = new Partition(0);
def insert = data.getInserter(domainArg, domainPart);
InserterUtils.loadDelimitedData(insert, dir+"sn_domain.txt");


Partition rangePart = new Partition(10);
insert = data.getInserter(rangeArg, rangePart)
InserterUtils.loadDelimitedData(insert, dir+"sn_range.txt");

//NOTE THAT ADDITION IS DONE WITH THE TRUTH VALUES HERE - REMOVE (if needed) the TRUTH values WHILE SEEDING
Partition seedPart = new Partition(100);
insert = data.getInserter(inferable, seedPart)
InserterUtils.loadDelimitedDataTruth(insert, dir+"sn_seed.txt");


//Partition to store the results
Partition resultPart = new Partition(1000);

/* RUNNING THE INFERENCE with hard defined weights
 * resultPart as first argument
 * Close the predicates domainArg, rangeArg as these are observed
 * pass the other partitions as observations
 * NOTE- seedPart is observed part of INFERABLE predicate 
 * so INFERABLE predicate is partially observed and read/write while others are completely observed and READ only.
 */

Database db = data.getDatabase(resultPart, [domainArg, rangeArg] as Set, domainPart, rangePart, seedPart);
LazyMPEInference inferenceApp = new LazyMPEInference(m, db, config);
inferenceApp.mpeInference();
inferenceApp.close();

/* Printing the results */
println "Inference results with hand-defined weights:"
for (GroundAtom atom : Queries.getAllAtoms(db, inferable))
	println atom.toString() + "\t" + atom.getValue();

//database close to clear the buffer
db.close();








////////		WEIGHT LEARNING 		///////


/* 
 * Next, we want to learn the weights from data. For that, we need to have some evidence
 * data from which we can learn. In our example, that means we need to specify the 'true'
 * alignment, which we now load into a second partition. 
 
Partition trueDataPartition = new Partition(1);
insert = data.getInserter(samePerson, trueDataPartition)
InserterUtils.loadDelimitedDataTruth(insert, dir + "sn_align.txt");

/* 
 * Now, we can learn the weight, by specifying where the respective data fragments are stored
 * in the database (see above). In addition, we need to specify, which predicate we would like to
 * infer, i.e. learn on, which in our case is 'samePerson'.
 
Database trueDataDB = data.getDatabase(trueDataPartition, [samePerson] as Set);
LazyMaxLikelihoodMPE weightLearning = new LazyMaxLikelihoodMPE(m, db, trueDataDB, config);
weightLearning.learn();
weightLearning.close();

/*
 * Let's have a look at the newly learned weights.
 
println "Learned model:"
println m

/*
 * Now, we apply the learned model to a different social network alignment dataset. We load the 
 * dataset as before (this time into partition 2) and run inference. Finally we print the results.
 
Partition sn2 = new Partition(2);
insert = data.getInserter(name, sn2);
InserterUtils.loadDelimitedData(insert, dir+"sn2_names.txt");
insert = data.getInserter(knows, sn2);
InserterUtils.loadDelimitedData(insert, dir+"sn2_knows.txt");

Database db2 = data.getDatabase(sn2, [Name, Knows] as Set);
inferenceApp = new LazyMPEInference(m, db2, config);
result = inferenceApp.mpeInference();
inferenceApp.close();

println "Inference results on second social network with learned weights:"
for (GroundAtom atom : Queries.getAllAtoms(db2, SamePerson))
	println atom.toString() + "\t" + atom.getValue();
	
/* We close the Databases to flush writes 
db.close();
trueDataDB.close();
db2.close();

/**
 * This class implements the ExternalFunction interface so that it can be used
 * as an attribute similarity function within PSL.
 *
 * This simple implementation checks whether two strings are identical, in which case it returns 1.0
 * or different (returning 0.0).
 *
 * The package edu.umd.cs.psl.ui.functions.textsimilarity contains additional and
 * more sophisticated string similarity functions.
 */
class MyStringSimilarity implements ExternalFunction {
	
	@Override
	public int getArity() {
		return 2;
	}

	@Override
	public ArgumentType[] getArgumentTypes() {
		return [ArgumentType.String, ArgumentType.String].toArray();
	}
	
	@Override
	public double getValue(ReadOnlyDatabase db, GroundTerm... args) {
		return args[0].toString().equals(args[1].toString()) ? 1.0 : 0.0;
	}
	
}

package br.ufpe.cin.app.st;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import br.ufpe.cin.app.JFSTMerge;
import br.ufpe.cin.exceptions.st.PrintException;
import br.ufpe.cin.exceptions.st.StructuredMergeException;
import br.ufpe.cin.exceptions.st.TextualMergeException;
import br.ufpe.cin.files.st.FilesManager;
import br.ufpe.cin.files.st.FilesTuple;
import br.ufpe.cin.logging.st.LoggerFactory;
import br.ufpe.cin.mergers.st.StructuredMerge;
import br.ufpe.cin.mergers.st.TextualMerge;
import br.ufpe.cin.mergers.util.st.MergeConflict;
import br.ufpe.cin.mergers.util.st.MergeContextSt;
import br.ufpe.cin.mergers.util.st.MergeScenario;
import br.ufpe.cin.printers.st.Prettyprinter;
import br.ufpe.cin.statistics.st.Statistics;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

/**
 * Main class, responsible for performing <i>structured</i> merge in java files.
 * It also merges non java files, however, in these cases, traditional linebased
 * (unstructured) merge is invoked.
 * @author Guilherme
 */
public class STJFSTMerge {

	//log of activities
	private static final Logger LOGGER = LoggerFactory.make();

	//indicator of conflicting merge
	private static int conflictState = 0;

	//command line options
	@Parameter(names = "-f", arity = 3, description = "Files to be merged (mine, base, yours)")
	List<String> filespath = new ArrayList<String>();

	@Parameter(names = "-d", arity = 3, description = "Directories to be merged (mine, base, yours)")
	List<String> directoriespath = new ArrayList<String>();

	@Parameter(names = "-o", description = "Destination of the merged content. Optional. If no destination is specified, " + "then it will use \"yours\" as the destination for the merge. ")
	String outputpath = "";

	@Parameter(names = "-g", description = "Parameter to identify that the tool is being used as a git merge driver.")
	public static boolean isGit = false;

	@Parameter(names = "-c", description = "Parameter to disable cryptography during logs generation (true or false).",arity = 1)
	public static boolean isCryptographed = false;

	@Parameter(names = "-l", description = "Parameter to disable logging of merged files (true or false).",arity = 1)
	public static boolean logFiles = false;

	/**
	 * Merges merge scenarios, indicated by .revisions files. 
	 * This is mainly used for evaluation purposes.
	 * A .revisions file contains the directories of the revisions to merge in top-down order: 
	 * first revision, base revision, second revision (three-way merge).
	 * @param revisionsPath file path
	 */
	public MergeScenario mergeRevisions(String revisionsPath) {
		//disabling cryptography for performance improvement
		isCryptographed = false;

		MergeScenario scenario = null;
		try {
			//reading the .revisions file line by line to get revisions directories
			List<String> listRevisions = new ArrayList<>();
			BufferedReader reader = Files.newBufferedReader(Paths.get(revisionsPath));
			listRevisions = reader.lines().collect(Collectors.toList());
			if (listRevisions.size() != 3)
				throw new Exception("Invalid .revisions file!");

			//merging the identified directories
			if (!listRevisions.isEmpty()) {
				System.out.println("MERGING REVISIONS: \n" + listRevisions.get(0) + "\n" + listRevisions.get(1) + "\n" + listRevisions.get(2));
				String revisionFileFolder = (new File(revisionsPath)).getParent();
				String leftDir = revisionFileFolder + File.separator + listRevisions.get(0);
				String baseDir = revisionFileFolder + File.separator + listRevisions.get(1);
				String rightDir = revisionFileFolder + File.separator + listRevisions.get(2);

				List<FilesTuple> mergedTuples = mergeDirectories(leftDir, baseDir, rightDir, null);

				//using the name of the revisions directories as revisions identifiers
				scenario = new MergeScenario(revisionsPath, listRevisions.get(0), listRevisions.get(1), listRevisions.get(2), mergedTuples);

				//statistics
				Statistics.compute(scenario);

				//printing the resulting merged codes
				Prettyprinter.generateMergedScenario(scenario);
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred. See " + LoggerFactory.logfile + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
			LOGGER.log(Level.SEVERE, "", e);
			System.exit(-1);
		}
		return scenario;
	}

	/**
	 * Merges directories.
	 * @param leftDirPath (mine)
	 * @param baseDirPath (older)
	 * @param rightDirPath (yours)
	 * @param outputDirPath can be null, in this case, the output will only be printed in the console.
	 * @return merged files tuples
	 */
	public List<FilesTuple> mergeDirectories(String leftDirPath, String baseDirPath, String rightDirPath, String outputDirPath) {
		List<FilesTuple> filesTuple = FilesManager.fillFilesTuples(leftDirPath, baseDirPath, rightDirPath, outputDirPath, new ArrayList<String>());
		for (FilesTuple tuple : filesTuple) {
			File left = tuple.getLeftFile();
			File base = tuple.getBaseFile();
			File right = tuple.getRightFile();

			//merging the file tuple
			MergeContextSt context = mergeFiles(left, base, right, null);
			tuple.setContext(context);

			//printing the resulting merged code
			if (outputDirPath != null) {
				try {
					Prettyprinter.generateMergedTuple(tuple);
				} catch (PrintException pe) {
					System.err.println("An error occurred. See " + LoggerFactory.logfile + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
					LOGGER.log(Level.SEVERE, "", pe);
					System.exit(-1);
				}
			}
		}
		return filesTuple;
	}

	/**
	 * Three-way structured merge of the given .java files.
	 * @param left (mine) version of the file, or <b>null</b> in case of intentional empty file. 
	 * @param base (older) version of the file, or <b>null</b> in case of intentional empty file. 
	 * @param right (yours) version of the file, or <b>null</b> in case of intentional empty file. 
	 * @param outputFilePath of the merged file. Can be <b>null</b>, in this case, the output will only be printed in the console.
	 * @return context with relevant information gathered during the merging process.
	 */
	public MergeContextSt mergeFiles(File left, File base, File right, String outputFilePath) {
		FilesManager.validateFiles(left, base, right);
		if (!isGit) {
			System.out.println("MERGING FILES: \n" + ((left != null) ? left.getAbsolutePath() : "<empty left>") + "\n" + ((base != null) ? base.getAbsolutePath() : "<empty base>") + "\n" + ((right != null) ? right.getAbsolutePath() : "<empty right>"));
		}

		MergeContextSt context = new MergeContextSt(left, base, right, outputFilePath);

		//there is no need to call specific merge algorithms in equal or consistently changes files (fast-forward merge)
		if (FilesManager.areFilesDifferent(left, base, right, outputFilePath, context)) {
			long t0 = System.nanoTime();
			try {
				//running unstructured merge first is necessary due to future steps.
				context.unstructuredOutput = TextualMerge.merge(left, base, right, false);
				context.unstructuredMergeTime = System.nanoTime() - t0;

				context.structuredOutput = StructuredMerge.merge(left, base, right, context);
				context.structuredMergeTime = context.structuredMergeTime + (System.nanoTime() - t0);

				conflictState = checkConflictState(context);
			} catch (TextualMergeException tme) { //textual merge must work even when structured not, so this exception precedes others
				System.err.println("An error occurred. See " + LoggerFactory.logfile + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
				LOGGER.log(Level.SEVERE, "", tme);
				System.exit(-1);
			} catch (StructuredMergeException sme) {
				LOGGER.log(Level.WARNING, "", sme);
				context.structuredOutput = context.unstructuredOutput;
				context.structuredMergeTime = System.nanoTime() - t0;

				conflictState = checkConflictState(context);
			}
		}

		//printing the resulting merged code
		try {
			if(!isGit){
				Prettyprinter.printOnScreenMergedCode(context);
			}
			Prettyprinter.generateMergedFile(context, outputFilePath);
		} catch (PrintException pe) {
			System.err.println("An error occurred. See " + LoggerFactory.logfile + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
			LOGGER.log(Level.SEVERE, "", pe);
			System.exit(-1);
		}

		//computing statistics
		try {
			Statistics.compute(context);
		} catch (Exception e) {
			System.err.println("An error occurred. See " + LoggerFactory.logfile + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
			LOGGER.log(Level.SEVERE, "", e);
			System.exit(-1);
		}
		System.out.println("Merge files finished.");
		return context;
	}

	public static void main(String[] args) {
		//		JFSTMerge merger = new JFSTMerge();
		//		merger.run(args);
		//		System.exit(conflictState);
		
		new STJFSTMerge().mergeFiles(
				new File("C:/GGTS/workspaces/workspace_rsc2/stjFSTMerge/testfiles/toy2/left.java"),
				new File("C:/GGTS/workspaces/workspace_rsc2/stjFSTMerge/testfiles/toy2/base.java"),
				new File("C:/GGTS/workspaces/workspace_rsc2/stjFSTMerge/testfiles/toy2/right.java"),
				null);
		

/*		new STJFSTMerge().mergeFiles(
				new File("C:\\Users//Guilherme//Google Drive//P�s-Gradua��o//Pesquisa//Outros//running_examples//examples_jdime//samestmt//left.java"),
				new File("C:\\Users//Guilherme//Google Drive//P�s-Gradua��o//Pesquisa//Outros//running_examples//examples_jdime//samestmt//base.java"),
				new File("C:\\Users//Guilherme//Google Drive//P�s-Gradua��o//Pesquisa//Outros//running_examples//examples_jdime//samestmt//right.java"),
				null);
		
		new STJFSTMerge().mergeFiles(
				new File("testfiles/toy/left.java"), 
				new File("testfiles/toy/base.java"), 
				new File("testfiles/toy/right.java"), 
				null);*/

		/*		try {
			List<String> listRevisions = new ArrayList<>();
			BufferedReader reader;
			reader = Files.newBufferedReader(Paths.get("C:\\sample\\all.revisions"));
			listRevisions = reader.lines().collect(Collectors.toList());
			for(String r : listRevisions){
				new JFSTMerge().mergeRevisions(r);		
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}*/

	}

	private void run(String[] args) {
		JCommander commandLineOptions = new JCommander(this);
		try {
			commandLineOptions.parse(args);
			CommandLineValidator.validateCommandLineOptions(this);
			if (!filespath.isEmpty()) {
				mergeFiles(new File(filespath.get(0)), new File(filespath.get(1)), new File(filespath.get(2)), outputpath);
			} else if (!directoriespath.isEmpty()) {
				mergeDirectories(directoriespath.get(0), directoriespath.get(1), directoriespath.get(2), outputpath);
			}
		} catch (ParameterException pe) {
			System.err.println(pe.getMessage());
			commandLineOptions.setProgramName("JFSTMerge");
			commandLineOptions.usage();
		}
	}

	private int checkConflictState(MergeContextSt context) {
		List<MergeConflict> conflictList = FilesManager.extractMergeConflicts(context.structuredOutput);
		if (conflictList.size() > 0) {
			return 1;
		} else {
			return 0;
		}
	}
}
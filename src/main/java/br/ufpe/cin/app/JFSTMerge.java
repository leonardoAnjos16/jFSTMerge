package br.ufpe.cin.app;

import br.ufpe.cin.crypto.FileEncrypterDecrypter;
import br.ufpe.cin.exceptions.CryptoException;
import br.ufpe.cin.exceptions.PrintException;
import br.ufpe.cin.exceptions.SemistructuredMergeException;
import br.ufpe.cin.exceptions.TextualMergeException;
import br.ufpe.cin.files.FilesEncoding;
import br.ufpe.cin.files.FilesManager;
import br.ufpe.cin.files.FilesTuple;
import br.ufpe.cin.logging.LoggerFactory;
import br.ufpe.cin.mergers.SemistructuredMerge;
import br.ufpe.cin.mergers.TextualMerge;
import br.ufpe.cin.mergers.util.MergeConflict;
import br.ufpe.cin.mergers.util.MergeContext;
import br.ufpe.cin.mergers.util.MergeScenario;
import br.ufpe.cin.mergers.util.RenamingStrategy;
import br.ufpe.cin.mergers.util.converters.RenamingStrategyConverter;
import br.ufpe.cin.printers.Prettyprinter;
import br.ufpe.cin.statistics.Statistics;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.FileConverter;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Main class, responsible for performing <i>semistructured</i> merge in java files.
 * It also merges non java files, however, in these cases, traditional linebased
 * (unstructured) merge is invoked.
 * @author Guilherme
 */
public class JFSTMerge {

	//log of activities
	private static final Logger LOGGER = LoggerFactory.make();

	public static final double RENAMING_SIMILARITY_THRESHOLD = 0.7;  //a typical value of 0.7 (up to 1.0) is used, increase it for a more accurate comparison, or decrease for a more relaxed one.

	//indicator of conflicting merge
	private static int conflictState = 0;

	// EncrypterDecrypter
	private FileEncrypterDecrypter fileEncrypterDecrypter = new FileEncrypterDecrypter();

	//command line options
	@Parameter(arity = 3, description = "MinePath BasePath YoursPath", required = true, listConverter = FileConverter.class)
	List<File> files = new ArrayList<>();

	@Parameter(names = "-o", description = "Destination of the merged content. Optional. If no destination is specified, "
            + "then it will use \"yours\" as the destination for the merge. ")
	String outputpath = "";

	@Parameter(names = "-g", description = "Parameter to identify that the tool is being used as a git merge driver.")
	public static boolean isGit = false;

	@Parameter(names = "-u", description = "Parameter to disable writing unstructured merge output to file")
	public static boolean showUnstructuredOutput = true;

	@Parameter(names = "-c", description = "Parameter to disable cryptography during logs generation (true or false).", arity = 1)
	private boolean isCryptographed = true;
  
	@Parameter(names = "-l", description = "Parameter to disable logging of merged files (true or false).",arity = 1)
	public static boolean logFiles = true;

	@Parameter(names = "--files-encoding", description = "Determines the encoding of the input files. If not specified," +
			"the tool tries to infer the encoding of the files. If this fails, it assumes the files are encoded in UTF-8.", arity = 3)
	private List<String> filesEncoding = new ArrayList<>();

	@Parameter(names = "--ignore-space-change", description = "Treats lines with the indicated type of whitespace change as unchanged for "
			+ "the sake of a three-way merge. Whitespace changes mixed with other changes to a line are not ignored.", arity = 1)
	public static boolean isWhitespaceIgnored = true;

	@Parameter(names = "-rn", description = "Parameter to enable keeping both methods on renaming conflicts.")
	public static boolean keepBothVersionsOfRenamedMethod = false;

	@edu.umd.cs.findbugs.annotations.SuppressFBWarnings("MS_SHOULD_BE_FINAL")
	@Parameter(names = {"-r", "--renaming-strategy"}, description = "Parameter to choose strategy on renaming conflicts.",
            converter = RenamingStrategyConverter.class)
	public static RenamingStrategy renamingStrategy = RenamingStrategy.SAFELY_MERGE_SIMILAR;

	@Parameter(names = "-m", description = "Shows extra messages detailing conflict causes.")
	public static boolean showConflictMessages = false;

	@Parameter(names = {"--handle-duplicate-declarations", "-hdd"}, description = "Detects situations where merging developers' contributions adds " +
			"declarations with the same signature to different areas of the same class.", arity = 1)
	public static boolean isDuplicatedDeclarationHandlerEnabled = true;

	@Parameter(names = {"--handle-initialization-blocks", "-hib"}, description = "Detects and avoid duplications caused by merge of blocks without identifiers," +
			"using textual similarity.", arity = 1)
	public static boolean isInitializationBlocksHandlerEnabled = true;

	@Parameter(names = {"--handle-initialization-blocks-multiple-blocks", "-hibmb"}, description = "Detects and avoids duplications, possible dependency"
			+ " and variable renaming conflicts caused by the merge of blocks without identifiers using"
			+ " using % of insertion and textual similarity.", arity = 1)
	public static boolean isInitializationBlocksHandlerMultipleBlocksEnabled = false;

	@Parameter(names = {"--handle-new-element-referencing-edited-one", "-hnereo"}, description = "Detects cases where a developer" +
			"add an element that references an edited one.", arity = 1)
	public static boolean isNewElementReferencingEditedOneHandlerEnabled = true;

	@Parameter(names = {"--handle-method-constructor-renaming-deletion", "-hmcrd"}, description = "Detects and solves conflicts caused by renaming or deletion, where" +
			"semistructured merge alone is unable to solve.", arity = 1)
	public static boolean isMethodAndConstructorRenamingAndDeletionHandlerEnabled = true;

	@Parameter(names = {"--handle-type-ambiguity-error", "-htae"}, description = "Detects cases where import statements share elements with the same name.",
			arity = 1)
	public static boolean isTypeAmbiguityErrorHandlerEnabled = true;

	@Parameter(names = {"--show-base", "--diff3-style"}, description = "Outputs base's contribution in merge conflicts.")
	public static boolean showBase = false;

	/**
	 * Merges merge scenarios, indicated by .revisions files.
	 * This is mainly used for evaluation purposes.
	 * A .revisions file contains the directories of the revisions to merge in top-down order:
	 * first revision, base revision, second revision (three-way merge).
	 * @param revisionsPath file path
	 */
	@edu.umd.cs.findbugs.annotations.SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
	public MergeScenario mergeRevisions(String revisionsPath) {
		//disabling cryptography for performance improvement
		isCryptographed = false;

		MergeScenario scenario = null;
		try(BufferedReader reader = Files.newBufferedReader(Paths.get(revisionsPath))) {
			//reading the .revisions file line by line to get revisions directories
			List<String> listRevisions = new ArrayList<>();
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
			logSevereExceptionAndQuit(e);
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
			MergeContext context = mergeFiles(left, base, right, null);
			tuple.setContext(context);

			//printing the resulting merged code
			if (outputDirPath != null) {
				try {
					Prettyprinter.generateMergedTuple(tuple);
				} catch (PrintException pe) {
					logSevereExceptionAndQuit(pe);
				}
			}
		}
		return filesTuple;
	}

	/**
	 * Three-way semistructured merge of the given .java files.
	 * @param left (mine) version of the file, or <b>null</b> in case of intentional empty file.
	 * @param base (older) version of the file, or <b>null</b> in case of intentional empty file.
	 * @param right (yours) version of the file, or <b>null</b> in case of intentional empty file.
	 * @param outputFilePath of the merged file. Can be <b>null</b>, in this case, the output will only be printed in the console.
	 * @return context with relevant information gathered during the merging process.
	 */
	public MergeContext mergeFiles(File left, File base, File right, String outputFilePath) {
		FilesManager.validateFiles(left, base, right);

		if(filesEncoding.isEmpty()) {
			FilesEncoding.analyseFiles(left, base, right);
		} else {
			FilesEncoding.setFilesEncoding(left, base, right, filesEncoding);
		}

		if (!isGit) {
			System.out.println("MERGING FILES: \n" + ((left != null) ? left.getAbsolutePath() : "<empty left>") + "\n" + ((base != null) ? base.getAbsolutePath() : "<empty base>") + "\n" + ((right != null) ? right.getAbsolutePath() : "<empty right>"));
		}

		MergeContext context = new MergeContext(left, base, right, outputFilePath);

		//there is no need to call specific merge algorithms in equal or consistenly changes files (fast-forward merge)
		if (FilesManager.areFilesDifferent(left, base, right, outputFilePath, context)) {
			long t0 = System.nanoTime();
			try {
				//running unstructured merge first is necessary due to future steps.
				context.unstructuredOutput = TextualMerge.merge(left, base, right, false);
				context.unstructuredMergeTime = System.nanoTime() - t0;

				context.semistructuredOutput = SemistructuredMerge.merge(left, base, right, context);
				context.semistructuredMergeTime = context.semistructuredMergeTime + (System.nanoTime() - t0);

				conflictState = checkConflictState(context);
			} catch (TextualMergeException tme) { //textual merge must work even when semistructured not, so this exception precedes others
				logSevereExceptionAndQuit(tme);
			} catch (SemistructuredMergeException sme) {
				LOGGER.log(Level.WARNING, "", sme);
				context.semistructuredOutput = context.unstructuredOutput;
				context.semistructuredMergeTime = System.nanoTime() - t0;

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
			logSevereExceptionAndQuit(pe);
		}

		//computing statistics
		try {
			decryptLogFiles();
			Statistics.compute(context);
			if(isCryptographed) {
				encryptLogFiles();
			}
		} catch (Exception e) {
			logSevereExceptionAndQuit(e);
		}

		System.out.println("Merge files finished.");
		return context;
	}

	public static void main(String[] args) {
		JFSTMerge merger = new JFSTMerge();
		merger.run(args);
		System.exit(conflictState);

		/*		new JFSTMerge().mergeFiles(
						new File("C:/Users/Guilherme/Desktop/test/projects/sisbol/revisions/rev_0533511_8d296b5/rev_left_0533511/sisbol-core/src/main/java/br/mil/eb/cds/sisbol/boletim/util/Messages.java"),
						new File("C:/Users/Guilherme/Desktop/test/projects/sisbol/revisions/rev_0533511_8d296b5/rev_base_7004707/sisbol-core/src/main/java/br/mil/eb/cds/sisbol/boletim/util/Messages.java"),
						new File("C:/Users/Guilherme/Desktop/test/projects/sisbol/revisions/rev_0533511_8d296b5/rev_right_8d296b5/sisbol-core/src/main/java/br/mil/eb/cds/sisbol/boletim/util/Messages.java"),
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

			if(areDirectories(files)) {
				mergeDirectories(files.get(0).getAbsolutePath(), files.get(1).getAbsolutePath(), files.get(2).getAbsolutePath(), outputpath);
			} else {
				mergeFiles(files.get(0), files.get(1), files.get(2), outputpath);
			}
		} catch (ParameterException pe) {
			System.err.println(pe.getMessage());
			commandLineOptions.setProgramName("JFSTMerge");
			commandLineOptions.usage();
		}
	}


	private void encryptLogFiles() throws CryptoException {
		String userHome = System.getProperty("user.home");
		Path statisticsFile = Paths.get(userHome, ".jfstmerge", "jfstmerge.statistics");
		Path filesFile = Paths.get(userHome, ".jfstmerge", "jfstmerge.files");

		fileEncrypterDecrypter.cipher(statisticsFile, statisticsFile);
		fileEncrypterDecrypter.cipher(filesFile, filesFile);
	}

	private void decryptLogFiles() {
		String userHome = System.getProperty("user.home");
		Path statisticsFile = Paths.get(userHome, ".jfstmerge", "jfstmerge.statistics");
		Path filesFile = Paths.get(userHome, ".jfstmerge", "jfstmerge.files");
		try {
			if(Files.exists(statisticsFile))
				fileEncrypterDecrypter.decipher(statisticsFile, statisticsFile);
			if(Files.exists(filesFile))
				fileEncrypterDecrypter.decipher(filesFile, filesFile);
		} catch(CryptoException | InvalidPathException e) {
			System.out.println("Log files are already decrypted.");
		}
	}

	private boolean areDirectories(List<File> files) {
		return files.stream().allMatch(File::isDirectory);
	}

	private int checkConflictState(MergeContext context) {
		List<MergeConflict> conflictList = FilesManager.extractMergeConflicts(context.semistructuredOutput);
		if (conflictList.size() > 0) {
			return 1;
		} else {
			return 0;
		}
	}

	/**
	 * Closes the log file.
	 */
	public void closeLogFile() {
		LOGGER.getHandlers()[0].close();
  }

	public void isCryptographyEnabled(boolean isCryptographed) {
		this.isCryptographed = isCryptographed;
  }

	public void setFilesEncoding(List<String> filesEncoding) {
		this.filesEncoding = filesEncoding;
	}

	private void logSevereExceptionAndQuit(Exception e) {
		System.err.println("An error occurred. See " + LoggerFactory.logFile() + " file for more details.\n Send the log to gjcc@cin.ufpe.br for analysis if preferable.");
		LOGGER.log(Level.SEVERE, "", e);
		System.exit(-1);
	}

}

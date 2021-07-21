package br.ufpe.cin.printers;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Pattern;

import br.ufpe.cin.mergers.util.IndentationUtils;
import de.ovgu.cide.fstgen.ast.AbstractFSTPrintVisitor;
import de.ovgu.cide.fstgen.ast.FSTTerminal;

/**
 * Visitor to retrieve FSTNodes' contents to be printed.
 * 
 * @author João Victor (jvsfc@cin.ufpe.br)
 */
public abstract class S3MPrettyPrinter extends AbstractFSTPrintVisitor {

    private final StringBuilder result;
    private final Queue<String> tokensCurrentLine;

    private final Pattern conflictPattern = Pattern
            .compile("<<<<<<< MINE(.*)(||||||| BASE)?(.*)=======(.*)>>>>>>> YOURS(.*)", Pattern.DOTALL);

    public S3MPrettyPrinter() {
        this.result = new StringBuilder();
        this.tokensCurrentLine = new LinkedList<String>();
    }

    private boolean printedStatementOnFirstLine = false;

    @Override
    public boolean visit(FSTTerminal terminal) {
        String body = terminal.getBody();
        String prefix = terminal.getSpecialTokenPrefix();

        String token = "";
        if (body.isEmpty()) {
            // Node has been removed, so the prefix is trimmed to avoid unnecessary blank lines
            token = IndentationUtils.removePostIndentationAndLineBreaks(prefix);
        } else if (hasConflict(body)) {
            // Node content is inside conflict, so the prefix is trimmed and a new line is added
            token = IndentationUtils.removePostIndentation(prefix) + "\n" + body;
        } else if (isFirstLineOfCode(prefix) && isImportOrPackageStatement(terminal)) {
            // Statements on the first line don't have line breaks before them
            if (printedStatementOnFirstLine) {
                token = "\n" + prefix + body;
            } else {
                token = prefix + body;
                printedStatementOnFirstLine = true;
            }
        } else {
            token = prefix + body;
        }

        printToken(token);
        return false;
    }

    private boolean isImportOrPackageStatement(FSTTerminal terminal) {
        return terminal.getType().equals("ImportDeclaration") || terminal.getType().equals("PackageDeclaration");
    }

    private boolean isFirstLineOfCode(String prefix) {
        return !prefix.contains("\n");
    }

    private boolean hasConflict(String content) {
        return conflictPattern.matcher(content).matches();
    }

    protected void printToken(String token) {
        tokensCurrentLine.add(token);
    }

    @Override
    protected void hintNewLine() {
        Iterator<String> it = tokensCurrentLine.iterator();
        while (it.hasNext()) {
            result.append(it.next());
        }

        reset();
    }

    private void reset() {
        tokensCurrentLine.clear();
    }

    public String getResult() {
        hintNewLine();
        return result.toString();
    }

}
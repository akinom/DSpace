/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.checker;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.dspace.checker.BitstreamInfo;
import org.dspace.checker.BitstreamInfoDAO;
import org.dspace.checker.CheckBitstreamIterator;
import org.dspace.checker.CheckerCommand;
import org.dspace.checker.ChecksumCheckResults;
import org.dspace.checker.ChecksumHistoryDAO;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.storage.bitstore.BitstreamStorageManager;

/**
 * <p>
 * Use to iterate over bitstreams selected based on information stored in MOST_RECENT_CHECKSUM
 * </p>
 *
 * @author Monika Mevenkamp
 */
public final class ChecksumWorker
{
    private static final Logger LOG = Logger.getLogger(ChecksumWorker.class);
    private static final String DEFAULT_EXCLUDES = ChecksumCheckResults.BITSTREAM_NOT_FOUND + ", " +
            ChecksumCheckResults.BITSTREAM_MARKED_DELETED + ", " +
            ChecksumCheckResults.CHECKSUM_ALGORITHM_INVALID;

    private static final String[] ACTION_LIST = {"check", "print", "history", "delete"};
    private static final int CHECK = 0;
    private static final int PRINT = 1;
    private static final int HISTORY = 2;
    private static final int DELETE = 3;
    private static final int DEFAULT_ACTION = CHECK;

    private Context context;
    private CheckBitstreamIterator iter;
    private BitstreamInfoDAO bitstreamInfoDAO = null;
    private ChecksumHistoryDAO checksumHistoryDAO = null;

    private ChecksumWorker(Context context, CheckBitstreamIterator iterator) {
        this.context = context;
        this.iter = iterator;
        bitstreamInfoDAO = new BitstreamInfoDAO(context.getDBConnection());
        checksumHistoryDAO = new ChecksumHistoryDAO(context.getDBConnection());
    }

    private void process(int action, int count, boolean verbose) throws SQLException {
        if (verbose)
        {
            System.out.println("# " + iter);
            System.out.println("# Action " + ACTION_LIST[action]);
        }

        if (verbose) {
            System.out.println("# Start Check for new bitstreams: "  + new Date());
        }
        bitstreamInfoDAO.updateMissingBitstreams();
        if (verbose) {
            System.out.println("# Done  Check for new bitstreams " + new Date());
        }

        int row = 0;
        while (iter.next())
        {


            switch (action) {
                case PRINT: break;
                case CHECK:
                    checkBitstream(verbose);
                    break;
                default:
                    System.out.println("" + row + ": " + iter.bitstream_id() + "\t" + " TODO" + ACTION_LIST[action]);
            }

            if (verbose || action == PRINT )
            {
                printBitstream(row, verbose);
                row++;
            } else {
                System.out.print("#");
                if (row % 80 == 0)
                {
                    System.out.println("");
                }
            }
            count--;
            if (count == 0)
            {
                break;
            }
        }
        System.out.println();
    }

    private void printBitstream(int row, boolean verbose) throws SQLException
    {
        System.out.println("" + row + " BITSTREAM."  + iter.bitstream_id() + " " + iter.result() +
                " internalId=" + iter.internalId() + " " +
                " delete=" + iter.deleted() + " " +
                " lastDate=" + iter.last_process_end_date() + " " );
        if (verbose) {
            System.out.println("" + row + " "  + iter.bitstream() + " " + iter.result() + " " +
                    " algo=" + iter.bitstream().getChecksumAlgorithm() +
                    " expected=" + iter.bitstream().getChecksum() +
                    " calculated=" + iter.checksum());

            System.out.print("" + row + " "  + iter.bitstream() + " " + iter.result() + " ");
            for (DSpaceObject parent = iter.bitstream().getParentObject(); parent != null; parent = parent.getParentObject())
            {
                System.out.print(Constants.typeText[parent.getType()] + ":" + parent.getHandle() + " ");
            }
            System.out.println();
        }
    }

    private void checkBitstream(boolean verbose)
    {
        int id = -1;
        BitstreamInfo calcInfo = null;
        String result = null;
        InputStream bitstream = null;
        try
        {
            id = iter.bitstream_id();
            calcInfo = new BitstreamInfo(id);
        } catch (SQLException e)
        {
            result = ChecksumCheckResults.BITSTREAM_INFO_NOT_FOUND;
        }

        if (calcInfo != null)
        {
            calcInfo.setProcessStartDate(new Date());
            try
            {
                calcInfo.setChecksumAlgorithm(calcInfo.getBitstream(context).getChecksumAlgorithm());
                if (! iter.deleted())
                {
                    bitstream = BitstreamStorageManager.retrieve(context, id);
                    String checksum = CheckerCommand.digestStream(bitstream, calcInfo.getChecksumAlgorithm());
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(iter.bitstream() + " checksum " + checksum + " storeCheckSum " + iter.bitstream().getChecksum());
                    }
                    calcInfo.setCalculatedChecksum(checksum);
                    result = CheckerCommand.compareChecksums(iter.bitstream().getChecksum(), checksum);
                } else {
                    calcInfo.setToBeProcessed(false);  // to be compatible with CheckerCommand.processDeletedBitstream
                    result = ChecksumCheckResults.BITSTREAM_MARKED_DELETED;
                }

            } catch (IOException e)
            {
                result = ChecksumCheckResults.BITSTREAM_NOT_FOUND;
                e.printStackTrace();
            }
            catch (NoSuchAlgorithmException e)
            {
                result = ChecksumCheckResults.CHECKSUM_ALGORITHM_INVALID;
                e.printStackTrace();
            }
            catch (SQLException e)
            {
                result = ChecksumCheckResults.BITSTREAM_NOT_PROCESSED;
                e.printStackTrace();
            }
        }

        calcInfo.setChecksumCheckResult(result);
        calcInfo.setProcessEndDate(new Date());

        if (verbose) {
            System.out.println(getClass().getName() + " " + calcInfo.toLongString());
        }

        // record new checksum and comparison result in db
        LOG.debug("> update bitstreamInfoDAO " + calcInfo.toLongString());
        bitstreamInfoDAO.update(calcInfo);
        LOG.debug("< update bitstreamInfoDAO");
        LOG.debug("> update checksumHistoryDAO " + calcInfo.toLongString());
        checksumHistoryDAO.insertHistory(calcInfo);
        LOG.debug("< update checksumHistoryDAO");


    }

    public static void main(String[] args)
    {
        Context context = null;
        try
        {
            context = new Context();
        } catch (SQLException e)
        {
            System.err.println("could not open database connection");
            System.err.println(e.getStackTrace());
            System.exit(1);
        }

        // set up command line parser
        CommandLineParser parser = new PosixParser();
        CommandLine line = null;
        boolean verbose = false;

        // create an options object and populate it
        Options options = new Options();

        options.addOption("a", "after", true, "Work on bitstreams last checked after given date");
        options.addOption("b", "before", true, "CWork on bitstreams last checked before given date");
        options.addOption("c", "count", true, "Work on at most the given number of bitstreams");
        options.addOption("d", "do", true, "action to apply to bitstreams, one of " + StringUtils.join(ACTION_LIST, ", ") +
                ", (default is " + ACTION_LIST[DEFAULT_ACTION] + ")");
        options.addOption("h", "help", false, "Help");
        options.addOption("i", "include_result", true, "Work on bitstreams whose last result is <RESULT>");
        options.addOption("l", "loop", false, "Work on bitstreams whose last result is not one of " + DEFAULT_EXCLUDES);
        options.addOption("r", "root", true, "Work on bitstream in given Community, Collection, Item, or on the given Bitstream, give root as handle or TYPE.ID)");
        options.addOption("v", "verbose", false, "Be verbose");
        options.addOption("x", "exclude_result", true, "Work on bitstreams whose last result is not one of the given results (use a comma separated list)");

        try
        {
            line = parser.parse(options, args);
            if (line.hasOption('h'))
            {
                printHelp(options);
                return;
            }

            verbose = line.hasOption('v');

            String with_result = line.hasOption('i') ? line.getOptionValue('i') : null;
            // TODO check whether valid check_sum_result
            Boolean loop = line.hasOption('l');
            String excludes = line.hasOption('x') ? line.getOptionValue('x') : null;
            if ((loop && (with_result != null || excludes != null)) || (with_result != null && excludes != null))
            {
                throw new RuntimeException("-x, -i, and -l options are mutually exclusive");
            }

            if (loop)
            {
                excludes = DEFAULT_EXCLUDES;
            }
            String[] exclude_results = null;
            if (excludes != null)
            {
                exclude_results = excludes.split(",");
                for (int i = 0; i < exclude_results.length; i++)
                {
                    exclude_results[i] = exclude_results[i].trim();
                    // TODO check whether valid check_sum_result
                }
            }


            DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US);
            Date before = line.hasOption('b') ? df.parse(line.getOptionValue('b')) : null;
            Date after = line.hasOption('a') ? df.parse(line.getOptionValue('a')) : null;

            DSpaceObject root = null;
            if (line.hasOption('r'))
            {
                root = DSpaceObject.fromString(context, line.getOptionValue('r'));
                if (root == null) {
                    throw new RuntimeException("No such DSpaceObject " + line.getOptionValue('r'));
                }
            }
            CheckBitstreamIterator iter = CheckBitstreamIterator.create(with_result, exclude_results, before, after, root, context);
            if (iter == null)
            {
                throw new RuntimeException("not enough data to create iterator");
            }

            int count = line.hasOption('c') ? new Integer(line.getOptionValue('c')) : -1;

            String actionStr = line.hasOption('d') ? line.getOptionValue('d') : ACTION_LIST[DEFAULT_ACTION];
            int action = -1;
            for (int i = 0; i < ACTION_LIST.length; i++)
            {
                if (actionStr.equals(ACTION_LIST[i]))
                {
                    action = i;
                }
            }
            if (action < 0)
            {
                throw new RuntimeException("Unknown do action " + actionStr);
            }

            new ChecksumWorker(context, iter).process(action, count, verbose);



        } catch (Exception e)
        {
            System.err.println(e.getMessage());
            printHelp(options);
            if (verbose)
            {
                System.err.println();
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /**
     * Print the help options for the user
     *
     * @param options that are available for the user
     */
    private static void printHelp(Options options)
    {
        HelpFormatter myhelp = new HelpFormatter();

        myhelp.printHelp("ChecksumWorker:\n", options);

        System.err.println("Give dates in the american style: mm/dd/yyyy eg 2/20/2013 for Feb 20th 2013");

    }

}

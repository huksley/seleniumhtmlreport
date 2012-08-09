package org.jvnet.hudson.plugins.seleniumhtmlreport;

import hudson.model.BuildListener;
import hudson.model.AbstractBuild;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * @author Marco Machmer
 */
public class TestResult implements Serializable {

    private final String resultFileName;
    private String result = "";
    private int totalTime = 0;
    private int numTestPasses = 0;
    private int numTestFailures = 0;
    private int numCommandPasses = 0;
    private int numCommandFailures = 0;
    private int numCommandErrors = 0;

    private TestResult(String resultFileName) {
        super();
        this.resultFileName = resultFileName;
    }

    public int getNumTestPasses() {
        return this.numTestPasses;
    }

    public int getNumTestFailures() {
        return this.numTestFailures;
    }

    public int getNumCommandPasses() {
        return this.numCommandPasses;
    }

    public int getNumCommandFailures() {
        return this.numCommandFailures;
    }

    public int getNumCommandErrors() {
        return this.numCommandErrors;
    }

    public int getNumTestTotal() {
        return getNumTestPasses() + getNumTestFailures();
    }

    public String getResult() {
        return this.result;
    }

    public int getTotalTime() {
        return this.totalTime;
    }

    public String getResultFileName() {
        return this.resultFileName;
    }

    public static TestResult parse(AbstractBuild<?,?> build, BuildListener listener, String resultFileName, File seleniumReportsDir) throws IOException, SAXException {
    	try {
	        TestResult result = new TestResult(resultFileName);
	        if (listener != null) {
	        	listener.getLogger().println("parsing resultFile " + result.getResultFileName());
	        }
	        File reportFile = getReportFileFor(build, result, seleniumReportsDir);
	        InfoParser parser = new InfoParser(reportFile, listener);
	        result.result = parser.getString("result:");
	        result.totalTime = parser.getInt("totalTime:") / 1000;
	        result.numTestPasses = parser.getInt("numTestPasses:");
	        result.numTestFailures = parser.getInt("numTestFailures:");
	        result.numCommandPasses = parser.getInt("numCommandPasses:");
	        result.numCommandFailures = parser.getInt("numCommandFailures:");
	        result.numCommandErrors = parser.getInt("numCommandErrors:");
	        return result;
    	} catch (SAXException e) {
    		// Try to parse as loggin-selenium report
    		return parseLoggingSeleniumReport(build, listener, resultFileName, seleniumReportsDir);
    	}
    }
    
    /**
     * Parses report from logging-selenium project.
     */
    public static TestResult parseLoggingSeleniumReport(AbstractBuild<?,?> build, BuildListener listener, String resultFileName, File seleniumReportsDir) throws IOException, SAXException {
        TestResult result = new TestResult(resultFileName);
        if (listener != null) {
        	listener.getLogger().println("parsing resultFile " + result.getResultFileName());
        }
        File reportFile = getReportFileFor(build, result, seleniumReportsDir);
        InfoParser parser = new HtmlInfoParser(reportFile, listener);
        result.result = parser.getString("last failed message:");
        if (result.result == null) {
        	result.result = "OK";
        }
        result.totalTime = parser.getInt("test-duration [ms]:");
        result.numCommandPasses = parser.getInt("commands processed:");
        result.numCommandFailures = parser.getInt("failed commands:", 0);
        result.numCommandErrors = 0;
        
        if (result.getNumCommandErrors() + result.getNumCommandErrors() == 0) {
            result.numTestPasses = 1;
            result.numTestFailures = 0;
        } else {
        	result.numTestPasses = 0;
            result.numTestFailures = 1;
        }
        return result;
    }

    protected static File getReportFileFor(final AbstractBuild<?,?> build, final TestResult testResult, final File seleniumReportsDir) {
        return new File(seleniumReportsDir + "/" + testResult.getResultFileName());
    }
    
    private static class InfoParser {
        protected final File reportFile;
        protected final BuildListener listener;

        public InfoParser(File reportFile, BuildListener listener) {
            super();
            this.reportFile = reportFile;
			this.listener = listener;
        }

        public String getString(final String infoName) throws IOException, SAXException {
            return retrieve(infoName);
        }
        
        public int getInt(final String infoName, int def) throws IOException, SAXException {
        	String v = retrieve(infoName);
        	if (v == null) {
        		return def;
        	}
        	
        	try {
        		v = v.replaceAll("\\D+", "");
        		return Integer.parseInt(v);
        	} catch (NumberFormatException e) {
        		if (listener != null) {
        			listener.getLogger().println("Can`t parse " + v + " as " + infoName);
        		}
        		return def;
        	}
        }

        public int getInt(final String infoName) throws IOException, SAXException {
        	return getInt(infoName, -1);
        }

        protected String retrieve(final String infoName) throws IOException, SAXException {
            try {
                return parseFor(infoName);
            } catch (ParserConfigurationException e) {
                throw new IOException(e);
            }
        }

        protected String parseFor(final String infoName) throws ParserConfigurationException, SAXException, IOException {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setValidating(false);
            factory.setNamespaceAware(false);

            SAXParser saxParser = factory.newSAXParser();
            ReadInfoHandler riHandler = new ReadInfoHandler(infoName);
            try {
                saxParser.parse(this.reportFile, riHandler);
            } catch (BreakParsingException e) {
                // break parsing
            }
            return riHandler.getInfo();
        }
    }

    private static class ReadInfoHandler extends DefaultHandler {
        private final String infoName;
        private String characters;
        private boolean nextIsInfo = false;
        private String info;

        public ReadInfoHandler(String infoName) {
            super();
            this.infoName = infoName;
        }

        public String getInfo() {
            return this.info;
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if ("td".equals(qName) || "td".equalsIgnoreCase(localName)) {
                if (this.nextIsInfo) {
                    this.info = this.characters;
                    this.nextIsInfo = false;
                    throw new BreakParsingException();
                } else
                if (this.characters.equals(this.infoName)) {
                    this.nextIsInfo = true;
                } else {
                	// "Skipping cell: " + this.characters
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            this.characters = new String(ch, start, length).trim();
        }
    }
    
    /**
     * Uses http://htmlparser.sourceforge.net library to be able to parse HTML4 files. 
     */
    private static class HtmlInfoParser extends InfoParser {

		public HtmlInfoParser(File reportFile, BuildListener listener) {
			super(reportFile, listener);
		}
        
        protected String parseFor(final String infoName) throws SAXException, IOException, ParserConfigurationException {
        	XMLReader htmlReader = org.xml.sax.helpers.XMLReaderFactory.createXMLReader ("org.htmlparser.sax.XMLReader");
			ReadInfoHandler riHandler = new ReadInfoHandler(infoName);
			try {
				htmlReader.setContentHandler(riHandler);
				htmlReader.parse(this.reportFile.toURI().toString());
			} catch (BreakParsingException e) {
				// break parsing
			}
			String info = riHandler.getInfo();
			if ((info == null || info.trim().equals("")) && listener != null) {
				listener.getLogger().println(infoName + " not found in result file!");
			}
			return info;
        }
    }
    
    private static class BreakParsingException extends SAXException {
        public BreakParsingException() {
            super();
        }
    }
}

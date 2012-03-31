// File selector methods
import static groovy.io.FileType.*

File.metaClass.resolve = { Object name -> new File(delegate, name.toString()) }
File.metaClass.getAt = { String name -> new File(delegate, name) }
File.metaClass.listFiles = { c -> delegate.isDirectory() ? delegate.listFiles().findAll(c) : []}

File.metaClass.isVideo = { _types.getFilter("video").accept(delegate) }
File.metaClass.isAudio = { _types.getFilter("audio").accept(delegate) }
File.metaClass.isSubtitle = { _types.getFilter("subtitle").accept(delegate) }
File.metaClass.isVerification = { _types.getFilter("verification").accept(delegate) }
File.metaClass.isArchive = { _types.getFilter("archive").accept(delegate) }

File.metaClass.getDir = { getParentFile() }
File.metaClass.hasFile = { c -> isDirectory() && listFiles().find(c) }

String.metaClass.getFiles = { c -> new File(delegate).getFiles(c) }
File.metaClass.getFiles = { c -> def files = []; traverse(type:FILES) { files += it }; return c ? files.findAll(c).sort() : files.sort() }
List.metaClass.getFiles = { c -> findResults{ it.getFiles(c) }.flatten().unique() }

String.metaClass.getFolders = { c -> new File(delegate).getFolders(c) }
File.metaClass.getFolders = { c -> def folders = []; traverse(type:DIRECTORIES, visitRoot:true) { folders += it }; return c ? folders.findAll(c).sort() : folders.sort() }
List.metaClass.getFolders = { c -> findResults{ it.getFolders(c) }.flatten().unique() }

String.metaClass.eachMediaFolder = { c -> new File(delegate).eachMediaFolder(c) }
File.metaClass.eachMediaFolder = { c -> getFolders{ it.hasFile{ it.isVideo() } }.each(c) }
List.metaClass.eachMediaFolder = { c -> getFolders{ it.hasFile{ it.isVideo() } }.each(c) }


// File utility methods
import static net.sourceforge.tuned.FileUtilities.*

File.metaClass.getNameWithoutExtension = { getNameWithoutExtension(delegate.getName()) }
File.metaClass.getPathWithoutExtension = { new File(delegate.getParentFile(), getNameWithoutExtension(delegate.getName())).getPath() }
File.metaClass.getExtension = { getExtension(delegate) }
File.metaClass.hasExtension = { String... ext -> hasExtension(delegate, ext) }
File.metaClass.isDerived = { f -> isDerived(delegate, f) }
File.metaClass.validateFileName = { validateFileName(delegate) }
File.metaClass.validateFilePath = { validateFilePath(delegate) }
File.metaClass.moveTo = { f -> moveRename(delegate, f instanceof File ? f : new File(f.toString())) }
File.metaClass.copyTo = { dir -> copyAs(delegate, new File(dir, delegate.getName())) }
List.metaClass.mapByFolder = { mapByFolder(delegate) }
List.metaClass.mapByExtension = { mapByExtension(delegate) }
String.metaClass.getExtension = { getExtension(delegate) }
String.metaClass.hasExtension = { String... ext -> hasExtension(delegate, ext) }
String.metaClass.validateFileName = { validateFileName(delegate) }
String.metaClass.validateFilePath = { validateFilePath(delegate) }


// Parallel helper
import java.util.concurrent.*

def parallel(List closures, int threads = Runtime.getRuntime().availableProcessors()) {
	def tasks = closures.collect { it as Callable }
	return Executors.newFixedThreadPool(threads).invokeAll(tasks).collect{ _guarded { it.get() } }
}


// Web and File IO helpers
import java.nio.ByteBuffer
import java.nio.charset.Charset
import static net.sourceforge.filebot.web.WebRequest.*

URL.metaClass.getText = { readAll(getReader(delegate.openConnection())) }
URL.metaClass.getHtml = { new XmlParser(new org.cyberneko.html.parsers.SAXParser()).parseText(delegate.getText()) }
URL.metaClass.fetch = { fetch(delegate) }
ByteBuffer.metaClass.getText = { csn = "utf-8" -> Charset.forName(csn).decode(delegate.duplicate()).toString() }
ByteBuffer.metaClass.getHtml = { csn = "utf-8" -> new XmlParser(new org.cyberneko.html.parsers.SAXParser()).parseText(delegate.getText(csn)) }

URL.metaClass.get = { delegate.getText() }
URL.metaClass.post = { Map parameters -> post(delegate.openConnection(), parameters) }
URL.metaClass.post = { byte[] data, contentType = 'application/octet-stream' -> post(delegate.openConnection(), data, contentType) }
URL.metaClass.post = { String text, csn = 'utf-8' -> delegate.post(text.getBytes(csn), 'text/plain') }

ByteBuffer.metaClass.saveAs = { f -> f = f instanceof File ? f : new File(f.toString()); writeFile(delegate.duplicate(), f); f.absolutePath };
URL.metaClass.saveAs = { f -> fetch(delegate).saveAs(f) }
String.metaClass.saveAs = { f, csn = "utf-8" -> Charset.forName(csn).encode(delegate).saveAs(f) }

def telnet(host, int port, csn = 'utf-8', Closure handler) {
	def socket = new Socket(host, port)
	try {
		handler.call(new PrintStream(socket.outputStream, true, csn), socket.inputStream.newReader(csn))
	} finally {
		socket.close()
	}
}


// Template Engine helpers
import groovy.text.XmlTemplateEngine
import groovy.text.GStringTemplateEngine
import net.sourceforge.filebot.format.PropertyBindings
import net.sourceforge.filebot.format.UndefinedObject

Object.metaClass.applyXmlTemplate = { template -> new XmlTemplateEngine("\t", false).createTemplate(template).make(new PropertyBindings(delegate, new UndefinedObject(""))).toString() }
Object.metaClass.applyTextTemplate = { template -> new GStringTemplateEngine().createTemplate(template).make(new PropertyBindings(delegate, new UndefinedObject(""))).toString() }


// Shell helper
import static com.sun.jna.Platform.*

def execute(Object... args) {
	def cmd = args.toList()*.toString()
	if (isWindows()) {
		// normalize file separator for windows and run with cmd so any executable in PATH will just work
		cmd = ['cmd', '/c'] + cmd
	}
	
	if (!_args.trustScript) {
		_log.severe("Execute failed: Script is not trusted: " + cmd)
		return -1
	}
	
	// run command and print output
	def process = cmd.execute()
	process.waitForProcessOutput(System.out, System.err)
	
	return process.exitValue()
}


// WatchService helper
import net.sourceforge.filebot.cli.FolderWatchService

def createWatchService(Closure callback, List folders, boolean watchTree) {
	// sanity check
	folders.find{ if (!it.isDirectory()) throw new Exception("Must be a folder: " + it) }
	
	// create watch service and setup callback
	def watchService = new FolderWatchService(true) {
		
		@Override
		def void processCommitSet(File[] fileset, File dir) {
			callback(fileset.toList())
		}
	}
	
	// collect updates for 5 minutes and then batch process
	watchService.setCommitDelay(5 * 60 * 1000)
	watchService.setCommitPerFolder(watchTree)
	
	// start watching given files
	folders.each { dir -> _guarded { watchService.watchFolder(dir) } }
	
	return watchService
}

File.metaClass.watch = { c -> createWatchService(c, [delegate], true) }
List.metaClass.watch = { c -> createWatchService(c, delegate, true) }


// Season / Episode helpers
import net.sourceforge.filebot.media.*
import net.sourceforge.filebot.similarity.*

def parseEpisodeNumber(path, strict = true) {
	def input = path instanceof File ? path.name : path.toString()
	def sxe = new SeasonEpisodeMatcher(SeasonEpisodeMatcher.DEFAULT_SANITY, strict).match(input)
	return sxe == null || sxe.isEmpty() ? null : sxe[0]
}

def parseDate(path) {
	def input = path instanceof File ? path.name : path.toString()
	return new DateMetric().parse(input)
}

def detectSeriesName(files, locale = Locale.ENGLISH) {
	def names = MediaDetection.detectSeriesNames(files instanceof Collection ? files : [new File(files.toString())], locale)
	return names == null || names.isEmpty() ? null : names.toList()[0]
}

def detectMovie(movieFile, strict = true, locale = Locale.ENGLISH) {
	def movies = MediaDetection.detectMovie(movieFile, OpenSubtitles, TheMovieDB, locale, strict)
	return movies == null || movies.isEmpty() ? null : movies.toList()[0]
}

def similarity(o1, o2) {
	return new NameSimilarityMetric().getSimilarity(o1, o2)
}

List.metaClass.sortBySimilarity = { prime, Closure toStringFunction = { obj -> obj.toString() } ->
	return delegate.sort{ a, b -> similarity(toStringFunction(b), prime).compareTo(similarity(toStringFunction(a), prime)) }
}



// CLI bindings
def rename(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.rename(_files(args), args.action as String, args.conflict as String, args.output as String, args.format as String, args.db as String, args.query as String, args.order as String, args.filter as String, args.lang as String, args.strict as Boolean) }
	}
}

def getSubtitles(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.getSubtitles(_files(args), args.db as String, args.query as String, args.lang as String, args.output as String, args.encoding as String, args.strict as Boolean) }
	}
}

def getMissingSubtitles(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.getMissingSubtitles(_files(args), args.db as String, args.query as String, args.lang as String, args.output as String, args.encoding as String, args.strict as Boolean) }
	}
}

def check(args) {
	synchronized (_cli) {
		_guarded { _cli.check(_files(args)) }
	}
}

def compute(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.compute(_files(args), args.output as String, args.encoding as String) }
	}
}

def extract(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.extract(_files(args), args.output as String, args.conflict as String) }
	}
}

def fetchEpisodeList(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.fetchEpisodeList(args.query as String, args.format as String, args.db as String, args.order as String, args.lang as String) }
	}
}

def getMediaInfo(args) { args = _defaults(args)
	synchronized (_cli) {
		_guarded { _cli.getMediaInfo(args.file as File, args.format as String) }
	}
}


/**
 * Resolve folders/files to lists of one or more files
 */
def _files(args) {
	def files = [];
	if (args.folder) {
		(args.folder as File).traverse(type:FILES, maxDepth:0) { files += it }
	}
	if (args.file) {
		if (args.file instanceof Iterable || args.file instanceof File[]) {
			files += args.file as List
		} else {
			files += args.file as File
		}
	}
	return files
}

/**
 * Fill in default values from cmdline arguments
 */
def _defaults(args) {
		args.action      = args.action     ?: _args.action
		args.conflict    = args.conflict   ?: _args.conflict
		args.query       = args.query      ?: _args.query
		args.filter      = args.filter     ?: _args.filter
		args.format      = args.format     ?: _args.format
		args.db          = args.db         ?: _args.db
		args.order       = args.order      ?: _args.order
		args.lang        = args.lang       ?: _args.lang
		args.output      = args.output     ?: _args.output
		args.encoding    = args.encoding   ?: _args.encoding
		args.strict      = args.strict     != null ? args.strict : !_args.nonStrict
		return args
}

/**
 * Catch and log exceptions thrown by the closure
 */
this.metaClass._guarded = { c -> try { return c.call() } catch (e) { _log.severe("${e.class.simpleName}: ${e.message}"); return null }}

/*
 * Copyright (C) 2010-2015, Martin Goellnitz
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA, 02110-1301, USA
 */
package jfs.sync.webdav;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import javax.xml.namespace.QName;
import jfs.conf.JFSConfig;
import jfs.sync.JFSFile;
import jfs.sync.JFSFileProducer;
import jfs.sync.base.AbstractJFSFileProducerFactory;
import jfs.sync.encryption.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Represents an external file and uses a WebDAV backend.
 *
 * @author Martin Goellnitz
 *
 */
public class JFSWebDavFile extends JFSFile {

    private static final String PROP_LAST_MODIFIED_TIME = "Win32LastModifiedTime";

    private static final QName QNAME_LAST_MODIFIED_TIME = new QName("urn:schemas-microsoft-com:", PROP_LAST_MODIFIED_TIME, "ns1");

    private static final DateFormat DATE_FORMAT;

    private static final Logger LOG = LoggerFactory.getLogger(JFSWebDavFile.class);

    /**
     * The retrieved file information object from the server.
     */
    private FileInfo info = null;

    /**
     * The list of included files.
     */
    private JFSFile[] list = null;

    /**
     * The server access object to use.
     */
    private Sardine access = null;

    private OutputStream output = null;

    private InputStream input = null;


    static {
        DATE_FORMAT = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.ROOT);
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }


    private FileInfo createFileInfo(String folder, DavResource resource) {
        LOG.debug("createFileInfo() {} [{}] {}", folder+resource.getName(), resource.isDirectory(), resource.getCustomProps());
        FileInfo result = new FileInfo();
        result.setCanRead(true);
        result.setCanWrite(true);
        result.setExists(true);
        result.setPath(folder);
        result.setName(resource.getName());
        result.setDirectory(resource.isDirectory());
        result.setSize(resource.isDirectory() ? 0 : resource.getContentLength());
        long time = 0;
        if (!resource.isDirectory()) {
            Date modificationDate = resource.getModified();
            String modifiedDateString = resource.getCustomProps().get(PROP_LAST_MODIFIED_TIME);
            LOG.warn("createFileInfo() custom properties for {}: {}", resource.getName(), resource.getCustomProps());
            if (modifiedDateString!=null) {
                try {
                    synchronized (DATE_FORMAT) {
                        modificationDate = DATE_FORMAT.parse(modifiedDateString);
                    }
                    LOG.debug("createFileInfo() {} [{};{}]", modificationDate, time, resource.getModified().getTime());
                } catch (Exception e) {
                    LOG.error("createFileInfo()", e);
                } // try/catch
            } // if
            time = modificationDate.getTime();
        } // if
        result.setModificationDate(time);
        return result;
    } // createFileInfo()


    private String getUrl(String urlSegment) {
        String url = urlSegment;
        try {
            url = URLEncoder.encode(url, "UTF-8").replace("%2F", "/").replace("%3A", ":").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            LOG.error("getUrl()", e);
        } // try/catch
        return url;
    }


    /**
     * Creates a new external file for a certain path using a specific file producer.
     *
     * @param access
     * The server access object to use.
     * @param fileProducer
     * The assigned file producer.
     * @param path
     * The path to create the external file for.
     */
    public JFSWebDavFile(Sardine access, JFSFileProducer fileProducer, String path, boolean isDirectory) {
        super(fileProducer, path);

        this.access = access;

        String url = (fileProducer.getRootPath()+path).replace('\\', '/');
        String[] pathAndName = AbstractJFSFileProducerFactory.getPathAndName(url, "/");

        info = new FileInfo();
        info.setCanRead(true);
        info.setCanWrite(true);
        info.setPath(pathAndName[0]);
        info.setName(pathAndName[1]);
        info.setDirectory(isDirectory);

        if (LOG.isDebugEnabled()) {
            LOG.debug("(_) "+(info.isDirectory() ? "d" : "-")+(info.isExists() ? "e" : "-")+" | "+info.getPath()+"/"+info.getName());
        } // if
        try {
            String folderUrl = getUrl(pathAndName[0])+"/";
            List<DavResource> parentListing = ((JFSWebDavFileProducer) getFileProducer()).getListing(folderUrl);
            for (DavResource resource : parentListing) {
                if (pathAndName[1].equals(resource.getName())) {
                    info = createFileInfo(pathAndName[0], resource);
                } // if
            } // for
        } catch (IOException ioe) {
            LOG.error("() getting parent folder list for '"+pathAndName[0]+"': "+ioe.getMessage());
        } // try/catch

        if (LOG.isInfoEnabled()) {
            LOG.info("() "+(info.isDirectory() ? "d" : "-")+(info.isExists() ? "e" : "-")+" | "+info.getPath()+"/"+info.getName());
        } // if
    } // JFSWebDavFile()


    /**
     * Creates a new external root file and reads the structure from server.
     *
     * @param access
     * The server access object to use.
     * @param fileProducer
     * The assigned file producer.
     */
    public JFSWebDavFile(Sardine access, JFSFileProducer fileProducer) {
        this(access, fileProducer, "", true);
    } // JFSWebDavFile()


    /**
     * @see JFSFile#canRead()
     */
    @Override
    public boolean canRead() {
        return info.isCanRead();
    }


    /**
     * @see JFSFile#canWrite()
     */
    @Override
    public boolean canWrite() {
        return info.isCanWrite();
    }


    /**
     * @see JFSFile#getInputStream()
     */
    @Override
    protected InputStream getInputStream() {
        String url = getUrl(info.getPath()+"/"+info.getName());
        LOG.debug("getInputStream() url {}", url);
        try {
            input = access.get(url);
        } catch (IOException e) {
            LOG.error("getInputStream()", e);
        } // try/catch
        return input;
    } // getInputStream()


    private Sardine getAccess() {
        return access;
    } // getAccess()


    /**
     * @see JFSFile#getOutputStream()
     */
    @Override
    protected OutputStream getOutputStream() {
        LOG.debug("getOutputStream()");
        final String url = getUrl(info.getPath()+"/"+info.getName());

        try {
            OutputStream result = new com.gc.iotools.stream.os.OutputStreamToInputStream<String>() {

                @Override
                protected String doRead(InputStream input) throws Exception {
                    getAccess().put(url, input);
                    return "";
                }

            };
            output = result;
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } // try/catch
    } // getOutputStream()


    /**
     * @see JFSFile#closeInputStream()
     */
    @Override
    protected void closeInputStream() {
        if (input!=null) {
            try {
                input.close();
            } catch (IOException e) {
                LOG.error("closeInputStream()", e);
            } // try/catch
            input = null;
        } // if
    } // closeInputStream()


    /**
     * @see JFSFile#closeOutputStream()
     */
    @Override
    protected void closeOutputStream() {
        if (output!=null) {
            try {
                output.close();
            } catch (IOException e) {
                LOG.error("closeOutputStream()", e);
            } // try/catch
            output = null;
        } // if
    } // closeOutputStream()


    /**
     * @see JFSFile#delete()
     */
    @Override
    public boolean delete() {
        boolean result = false;
        try {
            String url = getUrl(info.getPath()+"/"+getName());
            LOG.debug("delete() deleting {}", url);
            access.delete(url+(info.isDirectory() ? "/" : ""));
            result = true;
        } catch (IOException e) {
            LOG.error("delete()", e);
        } // try/catch
        return result;
    } // delete()


    /**
     * @see JFSFile#exists()
     */
    @Override
    public boolean exists() {
        return info.isExists();
    }


    /**
     * @see JFSFile#getLastModified()
     */
    @Override
    public long getLastModified() {
        return info.getModificationDate();
    }


    /**
     * @see JFSFile#getLength()
     */
    @Override
    public long getLength() {
        return info.getSize();
    }


    /**
     * @see JFSFile#getList()
     */
    @Override
    public JFSFile[] getList() {
        if (list==null) {
            list = new JFSWebDavFile[0];
            if (isDirectory()) {
                try {
                    String folder = info.getPath()+"/"+info.getName()+"/";
                    String url = getUrl(folder);
                    List<DavResource> listing = ((JFSWebDavFileProducer) getFileProducer()).getListing(url);
                    if (listing.size()>1) {
                        list = new JFSWebDavFile[listing.size()-1];
                        int rootLength = new URL(getFileProducer().getRootPath()).getPath().length();

                        int i = 0;
                        for (DavResource resource : listing) {
                            LOG.debug("getList({}) {} / {}", i, folder, resource.getPath());
                            if (!folder.endsWith(resource.getPath())) {
                                String path = resource.getPath().substring(rootLength);
                                if (resource.isDirectory()) {
                                    path = path.substring(0, path.length()-1);
                                } // if
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("getList("+i+") resource uri: "+path+(resource.isDirectory() ? "/" : ""));
                                } // if
                                list[i++] = new JFSWebDavFile(access, getFileProducer(), path, resource.isDirectory());
                            } // if
                        } // for
                    } // if
                } catch (Exception e) {
                    LOG.error("getList()", e);
                } // try/catch
            } // if
        } // getList()

        return list;
    } // getList()


    /**
     * @see JFSFile#getName()
     */
    @Override
    public String getName() {
        return info.getName();
    }


    /**
     * @see JFSFile#getPath()
     */
    @Override
    public String getPath() {
        return info.getPath()+"/"+getName();
    }


    /**
     * @see JFSFile#isDirectory()
     */
    @Override
    public boolean isDirectory() {
        return info.isDirectory();
    }


    /**
     * @see JFSFile#mkdir()
     */
    @Override
    public boolean mkdir() {
        boolean result = false;
        try {
            String url = getUrl(info.getPath()+"/"+info.getName());
            LOG.debug("mkdir() creating {}", url);
            access.createDirectory(url);
            result = true;
        } catch (IOException e) {
            LOG.error("mkdir()", e);
        } // try/catch
        return result;
    } // mkdir()


    /**
     * @see JFSFile#setLastModified(long)
     */
    @Override
    public boolean setLastModified(long time) {
        boolean success = false;

        info.setModificationDate(time);

        String url = getUrl(info.getPath()+"/"+info.getName())+(isDirectory() ? "/" : "");
        String modificationDate;
        synchronized (DATE_FORMAT) {
            modificationDate = DATE_FORMAT.format(new Date(time));
        }

        LOG.debug("setLastModified() setting time for {} to {}", url, modificationDate);
        List<QName> removeProps = new ArrayList<>(1);
        removeProps.add(QNAME_LAST_MODIFIED_TIME);
        Map<QName, String> addProps = new HashMap<>();
        addProps.put(QNAME_LAST_MODIFIED_TIME, modificationDate);
        QName qn = new QName("http://www.provocon.de/sync", "JFileSync", "sync");
        addProps.put(qn, modificationDate);
        try {
            List<DavResource> result = access.patch(url, addProps);
            LOG.info("setLastModified() result list size {}", result.size());
            success = (result.size()==1);
            if (success) {
                LOG.info("setLastModified() result custom props {}", result.get(0).getCustomProps());
            } // if
        } catch (IOException e) {
            LOG.error("setLastModified() failed for "+url, e);
        } // try/catch
        return success;
    } // setLastModified()


    /**
     * @see JFSFile#setReadOnly()
     */
    @Override
    public boolean setReadOnly() {
        if (JFSConfig.getInstance().isSetCanWrite()) {
            info.setCanWrite(false);
        } // if

        return true;
    } // setReadOnly()


    /**
     * @see JFSFile#preCopyTgt(JFSFile)
     */
    @Override
    protected boolean preCopyTgt(JFSFile srcFile) {
        info.setModificationDate(srcFile.getLastModified());
        // Set last modified and read-only only when file is no directory:
        if (!srcFile.isDirectory()) {
            info.setSize(srcFile.getLength());
            if (!srcFile.canWrite()) {
                info.setCanWrite(false);
            } // if
        } // if

        return true;
    } // preCopyTgt()


    /**
     * @see JFSFile#preCopySrc(JFSFile)
     */
    @Override
    protected boolean preCopySrc(JFSFile tgtFile) {
        return true;
    }


    /**
     * @see JFSFile#postCopyTgt(JFSFile)
     */
    @Override
    protected boolean postCopyTgt(JFSFile srcFile) {
        // Update information object after copy. This method is only
        // called if all operations were performed successfully:
        info.setDirectory(srcFile.isDirectory());
        info.setExists(srcFile.exists());
        info.setSize(srcFile.getLength());
        LOG.info("postCopyTgt() {}", getName());
        setLastModified(srcFile.getLastModified());

        return true;
    } // postCopyTgt()


    /**
     * @see JFSFile#postCopySrc(JFSFile)
     */
    @Override
    protected boolean postCopySrc(JFSFile tgtFile) {
        return true;
    }


    /**
     * @see JFSFile#flush()
     */
    @Override
    public boolean flush() {
        return true;
    }

} // JFSWebDavFile

/*
 * Copyright (c) 2008, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jdk.internal.misc.Unsafe;

import static sun.nio.fs.WindowsConstants.FILE_FLAG_BACKUP_SEMANTICS;
import static sun.nio.fs.WindowsConstants.FILE_FLAG_OPEN_REPARSE_POINT;
import static sun.nio.fs.WindowsConstants.FILE_SHARE_DELETE;
import static sun.nio.fs.WindowsConstants.FILE_SHARE_READ;
import static sun.nio.fs.WindowsConstants.FILE_SHARE_WRITE;
import static sun.nio.fs.WindowsConstants.GENERIC_READ;
import static sun.nio.fs.WindowsConstants.OPEN_EXISTING;
import static sun.nio.fs.WindowsNativeDispatcher.CloseHandle;
import static sun.nio.fs.WindowsNativeDispatcher.CreateFile;
import static sun.nio.fs.WindowsNativeDispatcher.DeleteFile;
import static sun.nio.fs.WindowsNativeDispatcher.FindClose;
import static sun.nio.fs.WindowsNativeDispatcher.FindFirstStream;
import static sun.nio.fs.WindowsNativeDispatcher.FindNextStream;
import static sun.nio.fs.WindowsNativeDispatcher.FirstStream;

/**
 * Windows emulation of NamedAttributeView using Alternative Data Streams
 */
// windows??????????????????"user"??????????????????????????????????????????"???????????????"??????????????????????????????
class WindowsUserDefinedFileAttributeView extends AbstractUserDefinedFileAttributeView {
    
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    
    private final WindowsPath file;     // ??????"user"?????????????????????????????????
    
    private final boolean followLinks;  // ????????????????????????????????????????????????????????????
    
    
    WindowsUserDefinedFileAttributeView(WindowsPath file, boolean followLinks) {
        this.file = file;
        this.followLinks = followLinks;
    }
    
    // ????????????"user"????????????????????????????????????
    @Override
    public List<String> list() throws IOException {
        if(System.getSecurityManager() != null) {
            checkAccess(file.getPathForPermissionCheck(), true, false);
        }
        
        return listUsingStreamEnumeration();
    }
    
    // ????????????"user"???????????????????????????name????????????????????????
    @Override
    public int size(String name) throws IOException {
        if(System.getSecurityManager() != null) {
            checkAccess(file.getPathForPermissionCheck(), true, false);
        }
        
        // wrap with channel
        FileChannel fileChannel = null;
        try {
            Set<OpenOption> opts = new HashSet<>();
            opts.add(StandardOpenOption.READ);
            if(!followLinks) {
                opts.add(WindowsChannelFactory.OPEN_REPARSE_POINT);
            }
            
            // ???":"????????????????????????"file:name"???file?????????????????????windows????????????????????????
            String path = join(file, name);
            
            // ??????/???????????????????????????????????????????????????????????????
            fileChannel = WindowsChannelFactory.newFileChannel(path, null, opts, 0L);
        } catch(WindowsException x) {
            x.rethrowAsIOException(join(file.getPathForPermissionCheck(), name));
        }
        
        try {
            // ???????????????(??????)???????????????
            long size = fileChannel.size();
            if(size>Integer.MAX_VALUE) {
                throw new ArithmeticException("Stream too large");
            }
            return (int) size;
        } finally {
            fileChannel.close();
        }
    }
    
    /*
     * ?????????"user"??????????????????????????????????????????name??????????????????????????????src???
     *
     * ?????????
     * UserDefinedFileAttributeView view = FIles.getFileAttributeView(path, UserDefinedFileAttributeView.class);
     * String name = "user.mimetype";
     * view.write(name, Charset.defaultCharset().encode("text/html"));
     */
    @Override
    public int write(String name, ByteBuffer src) throws IOException {
        if(System.getSecurityManager() != null) {
            checkAccess(file.getPathForPermissionCheck(), false, true);
        }
        
        /*
         * Creating a named stream will cause the unnamed stream to be created if it doesn't already exist.
         * To avoid this we open the unnamed stream for reading and hope it isn't deleted/moved while we create or replace the named stream.
         * Opening the file without sharing options may cause sharing violations with other programs that are accessing the unnamed stream.
         */
        long handle = -1L;
        try {
            int flags = FILE_FLAG_BACKUP_SEMANTICS;
            if(!followLinks) {
                flags |= FILE_FLAG_OPEN_REPARSE_POINT;
            }
            
            // ???????????????????????????????????????????????????
            handle = CreateFile(file.getPathForWin32Calls(), GENERIC_READ, (FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE), OPEN_EXISTING, flags);
        } catch(WindowsException x) {
            x.rethrowAsIOException(file);
        }
        
        try {
            Set<OpenOption> opts = new HashSet<>();
            if(!followLinks) {
                opts.add(WindowsChannelFactory.OPEN_REPARSE_POINT);
            }
            opts.add(StandardOpenOption.CREATE);
            opts.add(StandardOpenOption.WRITE);
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
            
            FileChannel fileChannel = null;
            try {
                // ???":"????????????????????????"file:name"???file?????????????????????windows????????????????????????
                String path = join(file, name);
                
                // ??????/???????????????????????????????????????????????????????????????
                fileChannel = WindowsChannelFactory.newFileChannel(path, null, opts, 0L);
            } catch(WindowsException x) {
                x.rethrowAsIOException(join(file.getPathForPermissionCheck(), name));
            }
            
            // write value (nothing we can do if I/O error occurs)
            try {
                // ???????????????????????????????????????????????????
                int rem = src.remaining();
    
                // ??????????????????????????????????????????
                while(src.hasRemaining()) {
                    // ????????????src???????????????????????????fileChannel????????????????????????????????????????????????
                    fileChannel.write(src);
                }
    
                // ????????????????????????
                return rem;
            } finally {
                // ????????????
                fileChannel.close();
            }
        } finally {
            // ???????????????????????????
            CloseHandle(handle);
        }
    }
    
    /*
     * ?????????"user"??????????????????????????????????????????name?????????????????????????????????dst???
     *
     * ?????????
     * UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
     * String name = "user.mimetype";
     * ByteBuffer dst = ByteBuffer.allocate(view.size(name));
     * view.read(name, dst);
     * dst.flip();
     * String value = Charset.defaultCharset().decode(dst).toString();  // ???????????????????????????????????????
     */
    @Override
    public int read(String name, ByteBuffer dst) throws IOException {
        if(System.getSecurityManager() != null) {
            checkAccess(file.getPathForPermissionCheck(), true, false);
        }
        
        // wrap with channel
        FileChannel fileChannel = null;
        try {
            Set<OpenOption> opts = new HashSet<>();
            opts.add(StandardOpenOption.READ);
            if(!followLinks) {
                opts.add(WindowsChannelFactory.OPEN_REPARSE_POINT);
            }
            
            // ???":"????????????????????????"file:name"???file?????????????????????windows????????????????????????
            String path = join(file, name);
            
            // ??????/???????????????????????????????????????????????????????????????
            fileChannel = WindowsChannelFactory.newFileChannel(path, null, opts, 0L);
        } catch(WindowsException x) {
            x.rethrowAsIOException(join(file.getPathForPermissionCheck(), name));
        }
        
        // read to EOF (nothing we can do if I/O error occurs)
        try {
            if(fileChannel.size()>dst.remaining()) {
                throw new IOException("Stream too large");
            }
            
            int total = 0;
            
            // ??????????????????????????????????????????
            while(dst.hasRemaining()) {
                // ???fileChannel??????????????????????????????????????????dst?????????????????????????????????
                int n = fileChannel.read(dst);
                if(n<0) {
                    break;
                }
                total += n;
            }
            
            // ??????????????????????????????
            return total;
        } finally {
            fileChannel.close();
        }
    }
    
    // ?????????"user"??????????????????????????????????????????name??????
    @Override
    public void delete(String name) throws IOException {
        if(System.getSecurityManager() != null) {
            checkAccess(file.getPathForPermissionCheck(), false, true);
        }
        
        // ??????file????????????????????????
        String path = WindowsLinkSupport.getFinalPath(file, followLinks);
        // ???????????????????????????
        String toDelete = join(path, name);
        try {
            // ????????????
            DeleteFile(toDelete);
        } catch(WindowsException x) {
            x.rethrowAsIOException(toDelete);
        }
    }
    
    /** syntax to address named streams */
    // ???":"????????????????????????"file:name"
    private String join(String file, String name) {
        if(name == null) {
            throw new NullPointerException("'name' is null");
        }
        return file + ":" + name;
    }
    
    // ???":"????????????????????????"file:name"???file?????????????????????windows????????????????????????
    private String join(WindowsPath file, String name) throws WindowsException {
        // ??????file?????????windows?????????????????????
        String path = file.getPathForWin32Calls();
        
        // ???":"????????????????????????"path:name"
        return join(path, name);
    }
    
    /** enumerates the file streams using FindFirstStream/FindNextStream APIs */
    // ????????????"user"????????????????????????????????????
    private List<String> listUsingStreamEnumeration() throws IOException {
        List<String> list = new ArrayList<>();
        
        try {
            // ?????????file????????????????????????(?????????????????????null)
            FirstStream first = FindFirstStream(file.getPathForWin32Calls());
            
            if(first != null) {
                long handle = first.handle();
                try {
                    /* first stream is always ::$DATA for files */
                    // ?????????????????????????????????????????????????????????::$DATA??????????????????
                    String name = first.name();
                    
                    // ????????????????????????????????????????????????????????????????????????????????????
                    if(!name.equals("::$DATA")) {
                        String[] segs = name.split(":");
                        list.add(segs[1]);
                    }
                    
                    // ???????????????????????????????????????????????????????????????
                    while((name = FindNextStream(handle)) != null) {
                        String[] segs = name.split(":");
                        list.add(segs[1]);
                    }
                } finally {
                    FindClose(handle);
                }
            }
        } catch(WindowsException x) {
            x.rethrowAsIOException(file);
        }
        
        return Collections.unmodifiableList(list);
    }
    
}

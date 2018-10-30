package com.urchin.release.mgt.model;

public enum BinaryType {
    LINUX_TAR("tar.bz2"),
    LINUX_DEB("deb"),
    WINDOWS("msi");

    private String extension;

    BinaryType(String extension){
        this.extension = extension;
    }

    public String getExtension(){
        return extension;
    }
}

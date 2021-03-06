package com.forsrc.common.core.tcc.dto;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TccDto implements java.io.Serializable {

    private static final long serialVersionUID = 4706144731706609711L;

    private Long id;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date create;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date update;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date expire;

    private Integer status;

    private Integer times;

    private String microservice;

    private int version;

    private List<TccLinkDto> tccLinks;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Date getCreate() {
        return create;
    }

    public void setCreate(Date create) {
        this.create = create;
    }

    public Date getExpire() {
        return expire;
    }

    public void setExpire(Date expire) {
        this.expire = expire;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Date getUpdate() {
        return update;
    }

    public void setUpdate(Date update) {
        this.update = update;
    }

    public List<TccLinkDto> getTccLinks() {
        return tccLinks;
    }

    public void setTccLinks(List<TccLinkDto> tccLinks) {
        this.tccLinks = tccLinks;
    }

    public Integer getTimes() {
        return times;
    }

    public void setTimes(Integer times) {
        this.times = times;
    }

    public String getMicroservice() {
        return microservice;
    }

    public void setMicroservice(String microservice) {
        this.microservice = microservice;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return String.format(
                "{\"id\":\"%s\", \"create\":\"%s\", \"update\":\"%s\", \"expire\":\"%s\", \"status\":\"%s\", \"times\":\"%s\", \"microservice\":\"%s\", \"version\":\"%s\", \"tccLinks\":\"%s\"}",
                id, create, update, expire, status, times, microservice, version, tccLinks);
    }

}

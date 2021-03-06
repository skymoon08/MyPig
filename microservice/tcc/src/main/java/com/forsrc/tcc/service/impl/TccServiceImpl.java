package com.forsrc.tcc.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;

import com.forsrc.common.core.sso.feignclient.UserTccFeignClient;
import com.forsrc.common.core.tcc.exception.TccAlreadyCancelException;
import com.forsrc.common.core.tcc.exception.TccAlreadyConfirmException;
import com.forsrc.common.core.tcc.exception.TccCancelException;
import com.forsrc.common.core.tcc.exception.TccConfirmException;
import com.forsrc.common.core.tcc.exception.TccException;
import com.forsrc.common.core.tcc.status.Status;
import com.forsrc.common.utils.SnowflakeIDGenerator;
import com.forsrc.tcc.dao.TccDao;
import com.forsrc.tcc.dao.TccLinkDao;
import com.forsrc.tcc.dao.mapper.TccLinkMapper;
import com.forsrc.tcc.dao.mapper.TccMapper;
import com.forsrc.tcc.domain.entity.Tcc;
import com.forsrc.tcc.domain.entity.TccLink;
import com.forsrc.tcc.service.TccService;

@Service
@Transactional(rollbackFor = { Exception.class })
public class TccServiceImpl implements TccService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TccServiceImpl.class);

    @Autowired
    @Qualifier("tccLoadBalancedOAuth2RestTemplate")
    private OAuth2RestTemplate tccLoadBalancedOAuth2RestTemplate;

    @Autowired
    private TccDao tccDao;

    @Autowired
    private TccLinkDao tccLinkDao;

    @Autowired
    private TccMapper tccMapper;

    @Autowired
    private TccLinkMapper tccLinkMapper;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private UserTccFeignClient userTccFeignClient;

    @Override
    @Transactional(readOnly = true)
    public Tcc get(Long id) {
        return tccDao.getOne(id);
    }

    @Override
    public Tcc save(Tcc tcc) {
        tcc.setId(SnowflakeIDGenerator.get().getId());
        tccDao.save(tcc);
        List<TccLink> links = tcc.getTccLinks();
        if (links != null && !links.isEmpty()) {
            for (TccLink link : links) {
                link.setId(SnowflakeIDGenerator.get().getId());
                link.setTccId(tcc.getId());
                link.setStatus(Status.TRY.getStatus());
            }
            tccLinkDao.saveAll(links);
        }
        return tcc;
    }

    @Override
    public Tcc update(Tcc tcc) {
        List<TccLink> links = tcc.getTccLinks();
        if (links != null && !links.isEmpty()) {
            tccLinkDao.saveAll(links);
        }
        return tccDao.save(tcc);
    }

    @Override
    public void delete(Long id) {
        tccDao.deleteById(id);
    }

    @Override
    public Tcc confirm(Long id, String accessToken) throws TccException{

        Tcc tcc = tccDao.getOne(id);
        LOGGER.info("--> confirm: {}", tcc);
        if (tcc == null) {
            throw new TccConfirmException(id, "Not found tcc: " + id);
        }

        if (new Date().compareTo(tcc.getExpire()) > 0) {
            LOGGER.info("--> Timeout: {} -> {}", tcc.getId(), tcc.getExpire());
            if (Status.TRY.getStatus() == tcc.getStatus().intValue()) {
                cancelTcc(tcc, accessToken);
            }
            return tcc;
        }

        // if (tcc.getTimes().intValue() > 10) {
        // tcc.setStatus(Status.CONFIRM_ERROR.getStatus());
        // this.update(tcc);
        // return tcc;
        // }
        if (tcc.getStatus() != null && Status.CANCEL.getStatus() == tcc.getStatus().intValue()) {
            throw new TccAlreadyCancelException(id, "Already confirmed, Tccs tatus: " + tcc.getStatus());
        }
        if (tcc.getStatus() != null && Status.CONFIRM.getStatus() == tcc.getStatus().intValue()) {
            // throw new TccAlreadyConfirmException(uuid, "Already confirmed, Tccs tatus: "
            // + tcc.getStatus());
            tcc.setStatus(Status.ALREADY_CONFIRMED.getStatus());
            return tcc;
        }
        if (tcc.getStatus() != null && Status.TRY.getStatus() != tcc.getStatus().intValue()) {
            throw new TccConfirmException(id, "Error tcc status: " + tcc.getStatus());
        }

        confirmTcc(tcc, accessToken);
        return tcc;
    }

    public Tcc confirmTcc(Tcc tcc, String accessToken) {

        boolean isError = false;
        List<TccLink> links = tcc.getTccLinks();
        for (TccLink link : links) {
            //String uri = String.format("%s%s", link.getUri(), "/confirm");
            //ResponseEntity<Void> response = send(uri, link.getPath().toString(), accessToken, HttpMethod.PUT, 1);
            ResponseEntity<Void> response = confirmTccLink(link.getResourceId(), accessToken, 1);
            LOGGER.info("--> response: {}", response);
            String tccLinkStatus = response.getHeaders().getFirst("tccLinkStatus");
            int status = StringUtils.isEmpty(tccLinkStatus) ? Status.ERROR.getStatus() : Integer.valueOf(tccLinkStatus);
            if (!HttpStatus.NO_CONTENT.equals(response.getStatusCode()) && status != Status.CONFIRM.getStatus()) {
                isError = true;
            }
//            TccLink tccLink4Update = tccLinkDao.getOne(link.getId());
//            tccLink4Update.setStatus(status);
//            tccLinkDao.save(tccLink4Update);
        }

        Tcc tcc4Update = tccDao.getOne(tcc.getId());
        tcc4Update.setTimes(tcc.getTimes() + 1);
        if (!isError) {
            tcc4Update.setStatus(Status.CONFIRM.getStatus());
        }
        tccDao.save(tcc4Update);
        this.jmsTemplate.convertAndSend("jms/queues/tcc", tcc.toString());
        return tcc4Update;
    }

    public ResponseEntity<Void> confirmTccLink(Long resourceId, String accessToken, final int retry) {
        if (accessToken == null) {
            accessToken = tccLoadBalancedOAuth2RestTemplate.getAccessToken().getValue();
        }
        try {
            return userTccFeignClient.confirm(resourceId, "Bearer " + accessToken);
        } catch (Exception e) {
            if (retry >= 0) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException ie) {
                }
                return confirmTccLink(resourceId, null, retry - 1);
            }
            if (e instanceof HttpStatusCodeException) {
                HttpStatusCodeException hsce = (HttpStatusCodeException)e;
                LOGGER.warn("--> {}: {} -> {}", e.getClass(), hsce.getStatusCode(), hsce.getResponseBodyAsString());
                return ResponseEntity
                        .status(hsce.getStatusCode())
                        .header("tccId", String.valueOf(resourceId))
                        .header("responseBody", hsce.getResponseBodyAsString())
                        .header("errorMessage", hsce.getMessage())
                        .headers(hsce.getResponseHeaders())
                        .build();
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("tccId", String.valueOf(resourceId))
                    //.header("responseBody", e.getResponseBodyAsString())
                    .header("errorMessage", e.getMessage())
                    //.headers(e.getResponseHeaders())
                    .build();
        }
    }

    @Override
    public Tcc cancel(Long id, String accessToken) throws TccException{
        Tcc tcc = tccDao.getOne(id);
        LOGGER.info("--> cancel: {}", tcc);
        if (tcc == null) {
            throw new TccCancelException(id, "Not found tcc: " + id);
        }
        if (new Date().compareTo(tcc.getExpire()) > 0) {
            LOGGER.info("--> Timeout: {} -> {}", tcc.getId(), tcc.getExpire());
            if (Status.TRY.getStatus() == tcc.getStatus().intValue()) {
                cancelTcc(tcc, accessToken);
            }
            return tcc;
        }
        if (tcc.getStatus() != null && Status.CANCEL.getStatus() == tcc.getStatus().intValue()) {
            // throw new TccCancelException(uuid, "Already canceled, Tccs tatus: " +
            // tcc.getStatus());
            tcc.setStatus(Status.ALREADY_CANCELED.getStatus());
            return tcc;
        }
        if (tcc.getStatus() != null && Status.CONFIRM.getStatus() == tcc.getStatus().intValue()) {
            throw new TccAlreadyConfirmException(id, "Already confirmed, Tccs tatus: " + tcc.getStatus());
        }
        if (tcc.getStatus() != null && Status.TRY.getStatus() != tcc.getStatus().intValue()) {
            throw new TccCancelException(id, "Error tcc status: " + tcc.getStatus());
        }

        cancelTcc(tcc, accessToken);
        return tcc;
    }

    private Tcc cancelTcc(Tcc tcc, String accessToken) {

        boolean isError = false;
        List<TccLink> links = tcc.getTccLinks();
        for (TccLink link : links) {
            //String uri = String.format("%s%s", link.getUri(), "/cancel");
            //ResponseEntity<Void> response = send(uri, link.getPath().toString(), accessToken, HttpMethod.DELETE, 1);
            ResponseEntity<Void> response = cancelTccLink(link.getResourceId(), accessToken, 1);
            LOGGER.info("--> response: {}", response);
            String tccLinkStatus = response.getHeaders().getFirst("tccLinkStatus");
            int status = StringUtils.isEmpty(tccLinkStatus) ? Status.ERROR.getStatus() : Integer.valueOf(tccLinkStatus);
            if (!HttpStatus.NO_CONTENT.equals(response.getStatusCode())) {
                isError = true;
            }
            link.setStatus(status);
        }
        if (!isError) {
            tcc.setStatus(Status.CANCEL.getStatus());
        } else {
            if (new Date().compareTo(tcc.getExpire()) > 0) {
                tcc.setStatus(Status.TCC_TIMEOUT.getStatus());
            }
        }
        this.update(tcc);
        this.jmsTemplate.convertAndSend("jms/queues/tcc", tcc.toString());
        return tcc;
    }

    public ResponseEntity<Void> cancelTccLink(Long resourceId, String accessToken, final int retry) {
        if (accessToken == null) {
            accessToken = tccLoadBalancedOAuth2RestTemplate.getAccessToken().getValue();
        }
        try {
            return userTccFeignClient.cancel(resourceId, "Bearer " + accessToken);
        } catch (Exception e) {
            if (retry >= 0) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException ie) {
                }
                return cancelTccLink(resourceId, null, retry - 1);
            }
            if (e instanceof HttpStatusCodeException) {
                HttpStatusCodeException hsce = (HttpStatusCodeException)e;
                LOGGER.warn("--> {}: {} -> {}", e.getClass(), hsce.getStatusCode(), hsce.getResponseBodyAsString());
                return ResponseEntity
                        .status(hsce.getStatusCode())
                        .header("tccId", String.valueOf(resourceId))
                        .header("responseBody", hsce.getResponseBodyAsString())
                        .header("errorMessage", hsce.getMessage())
                        .headers(hsce.getResponseHeaders())
                        .build();
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("tccId", String.valueOf(resourceId))
                    //.header("responseBody", e.getResponseBodyAsString())
                    .header("errorMessage", e.getMessage())
                    //.headers(e.getResponseHeaders())
                    .build();
        }
    }

    private ResponseEntity<Void> send(String uri, String id, String accessToken, HttpMethod httpMethod, int retry) {

        String url = String.format("%s/{id}", uri);
        LOGGER.info("--> ResponseEntity: {}/{}", uri, id);
        HttpHeaders requestHeaders = new HttpHeaders();
        List<MediaType> mediaTypeList = new ArrayList<MediaType>();
        mediaTypeList.add(MediaType.APPLICATION_JSON);
        requestHeaders.setAccept(mediaTypeList);
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken == null) {
            accessToken = tccLoadBalancedOAuth2RestTemplate.getAccessToken().getValue();
        }
        requestHeaders.set("Authorization", String.format("Bearer %s", accessToken));
        HttpEntity<?> requestEntity = new HttpEntity<Object>(requestHeaders);

        // ResponseEntity<Object> response = oauth2RestTemplate.exchange(url,
        // httpMethod, requestEntity, Object.class, id);

        try {
            ResponseEntity<Void> response = tccLoadBalancedOAuth2RestTemplate.exchange(url, httpMethod, requestEntity,
                    Void.class, id);
            LOGGER.info("--> ResponseEntity: {}", response);
            return response;
        } catch (Exception e) {
            LOGGER.warn("--> {}: {}", e.getClass(), e.getMessage());
            if (retry >= 0) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException ie) {
                }
                return resend(uri, id, null, httpMethod, --retry);
            }
            if (e instanceof HttpStatusCodeException) {
                HttpStatusCodeException hsce = (HttpStatusCodeException)e;
                LOGGER.warn("--> {}: {} -> {}", e.getClass(), hsce.getStatusCode(), hsce.getResponseBodyAsString());
                return ResponseEntity
                        .status(hsce.getStatusCode())
                        .header("tccId", id)
                        .header("responseBody", hsce.getResponseBodyAsString())
                        .header("errorMessage", hsce.getMessage())
                        .headers(hsce.getResponseHeaders())
                        .build();
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("tccId", id)
                    //.header("responseBody", e.getResponseBodyAsString())
                    .header("errorMessage", e.getMessage())
                    //.headers(e.getResponseHeaders())
                    .build();
        } 
    }

    public synchronized ResponseEntity<Void> resend(String url, String id, String accessToken, HttpMethod httpMethod,
            int retry) {

        tccLoadBalancedOAuth2RestTemplate.getOAuth2ClientContext().setAccessToken(null);
        LOGGER.warn("--> Retry {}: {}/{} -> {} -> {}", retry, url, id, httpMethod);
        try {
            TimeUnit.MILLISECONDS.sleep(2);
        } catch (InterruptedException ie) {
        }
        return send(url, id, null, httpMethod, --retry);

    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Tcc> getTryStatusList() {
        return tccMapper.getTryStatusList();
    }

    @Override
    public TccLink getTccLink(UUID id) {
        return tccLinkDao.getOne(id);
    }

    @Override
    public TccLink update(TccLink tccLink) {
        return tccLinkDao.save(tccLink);
    }

    @Override
    public Tcc getTccByResourceId(Long resourceId) {
        return tccMapper.getByTccLinkResourceId(resourceId);
    }

    @Override
    public TccLink getTccLinkByResourceId(Long resourceId) {
        return tccLinkMapper.getByResourceId(resourceId);
    }

    @Override
    public int setTccMicroservice(String microservice) {
        return tccMapper.setTccMicroservice(microservice);
    }

    @Transactional(readOnly = true)
    public List<Tcc> getTryStatusList(String microservice) {
        return tccMapper.getTryStatusListByMicroservice(microservice);
    }
}

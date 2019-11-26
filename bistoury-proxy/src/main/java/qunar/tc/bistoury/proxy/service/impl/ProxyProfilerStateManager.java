package qunar.tc.bistoury.proxy.service.impl;

import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import org.springframework.stereotype.Service;
import qunar.tc.bistoury.common.BistouryConstants;
import qunar.tc.bistoury.common.TypeResponse;
import qunar.tc.bistoury.proxy.communicate.agent.AgentConnection;
import qunar.tc.bistoury.proxy.communicate.agent.AgentConnectionStore;
import qunar.tc.bistoury.proxy.service.profiler.ProfilerDatagramHolder;
import qunar.tc.bistoury.proxy.service.profiler.ProfilerService;
import qunar.tc.bistoury.proxy.service.profiler.ProfilerSettingsManager;
import qunar.tc.bistoury.proxy.service.profiler.ProfilerStateManager;
import qunar.tc.bistoury.remoting.protocol.Datagram;
import qunar.tc.bistoury.serverside.bean.Profiler;
import qunar.tc.bistoury.serverside.bean.ProfilerSettings;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static qunar.tc.bistoury.common.BistouryConstants.REQ_PROFILER_START_STATE_SEARCH;
import static qunar.tc.bistoury.common.BistouryConstants.REQ_PROFILER_STOP;
import static qunar.tc.bistoury.proxy.util.ProfilerDatagramHelper.*;

/**
 * @author cai.wen created on 2019/10/30 16:54
 */
@Service
public class ProxyProfilerStateManager implements ProfilerStateManager {

    private static final ScheduledExecutorService SCHEDULED_EXECUTOR = Executors.newScheduledThreadPool(5);

    private final Map<String, ProfilerDatagramHolder> profilingDatagrams = Maps.newConcurrentMap();

    private final Map<String, ProfilerDatagramHolder> readyDatagrams = Maps.newConcurrentMap();

    private static final int defaultAdditionalSeconds = 60;

    private final Object obj = new Object();

    private final Cache<String, Object> profilerIdCache = CacheBuilder.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    private static final int delay = 5;

    @Resource
    private ProfilerService profilerService;

    @Resource
    private ProfilerSettingsManager profilerSettingsManager;

    @Resource
    private AgentConnectionStore agentConnectionStore;

    @PostConstruct
    public void init() {
        SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::waitStopState, 0, delay, TimeUnit.SECONDS);
        SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::waitStartState, 0, delay, TimeUnit.SECONDS);
    }

    private void waitStopState() {
        changeState(readyDatagrams);
    }

    private void waitStartState() {
        changeState(profilingDatagrams);
    }

    private void changeState(Map<String, ProfilerDatagramHolder> datagramHolderMap) {
        List<String> needRemoveIds = new ArrayList<>();
        for (Map.Entry<String, ProfilerDatagramHolder> datagramEntry : datagramHolderMap.entrySet()) {
            String profilerId = datagramEntry.getKey();
            ProfilerDatagramHolder datagramHolder = datagramEntry.getValue();
            if (datagramHolder.isExpired()) {
                needRemoveIds.add(profilerId);
                forceStop(datagramEntry.getValue().getAgentId(), profilerId);
                continue;
            }
            datagramHolder.decreaseTime(delay);
            agentConnectionStore.getConnection(datagramHolder.getAgentId())
                    .ifPresent(agentConn -> agentConn.write(datagramHolder.getDatagram()));
        }
        for (String profilerId : needRemoveIds) {
            datagramHolderMap.remove(profilerId);
        }
    }

    @Override
    public ProfilerSettings register(String agentId, String command) {
        ProfilerSettings settings = profilerSettingsManager.create(command);
        String profilerId = profilerService.prepareProfiler(agentId, settings);
        settings.setCommand(settings.getCommand().replace(BistouryConstants.PROFILER_ID, profilerId));
        Optional<AgentConnection> agentConnRef = agentConnectionStore.getConnection(agentId);
        if (!agentConnRef.isPresent()) {
            throw new RuntimeException("no connection for profiler id. profilerId: " + profilerId);
        }

        profilerIdCache.put(profilerId, obj);
        int readyDuration = 15;
        ProfilerDatagramHolder readyHolder = createStartStateSearchHolder(agentId, profilerId, readyDuration);
        readyDatagrams.put(profilerId, readyHolder);
        agentConnectionStore.getConnection(readyHolder.getAgentId())
                .ifPresent(agentConn -> agentConn.write(readyHolder.getDatagram()));
        return settings;
    }

    @Override
    public boolean isProfilerRequest(String id) {
        return profilerIdCache.getIfPresent(id) != null;
    }

    @Override
    public void dealProfiler(String profilesId, TypeResponse<Map<String, String>> response) {
        String type = response.getType();
        if (type == null) {
            return;
        }
        if (BistouryConstants.REQ_PROFILER_STATE_SEARCH.equals(type)) {
            Map<String, String> data = response.getData().getData();
            String stateSearchType = data.get("type");
            if (getResultState(response)) {
                return;
            }
            if (REQ_PROFILER_START_STATE_SEARCH.equals(stateSearchType)) {
                readyDatagrams.remove(profilesId);
                profilerService.startProfiler(profilesId);
                Profiler profiler = profilerService.getProfilerRecord(profilesId);
                int profilingDuration = profiler.getDuration() + defaultAdditionalSeconds;
                ProfilerDatagramHolder profilingHolder = createFinishStateSearchHolder(profiler.getAgentId(), profilesId, profilingDuration);
                profilingDatagrams.put(profiler.getProfilerId(), profilingHolder);
            } else if (BistouryConstants.REQ_PROFILER_FINNSH_STATE_SEARCH.equals(stateSearchType)) {
                profilingDatagrams.remove(profilesId);
                profilerService.stopProfiler(profilesId);
            }
        } else if (REQ_PROFILER_STOP.equals(type)) {
            if (getResultState(response)) {
                return;
            }
            profilerService.stopProfiler(profilesId);
        }
    }

    private boolean getResultState(TypeResponse<Map<String, String>> response) {
        Map<String, String> data = response.getData().getData();
        String state = data.get("state");
        return state == null || !Boolean.valueOf(state);
    }

    @Override
    public void searchStopState(String profilerId) {
        profilerIdCache.put(profilerId, obj);
        Profiler profiler = profilerService.getProfilerRecord(profilerId);
        String agentId = profiler.getAgentId();
        Datagram datagram = createFinishStateSearchDatagram(profilerId);
        agentConnectionStore.getConnection(agentId)
                .ifPresent(agentConn -> agentConn.write(datagram));
    }

    @Override
    public void forceStop(String agentId, String profilerId) {
        profilerIdCache.put(profilerId, obj);
        Datagram datagram = createStopDatagram(profilerId);
        agentConnectionStore.getConnection(agentId)
                .ifPresent(agentConn -> agentConn.write(datagram));
    }

    private ProfilerDatagramHolder createFinishStateSearchHolder(String agentId, String profilerId, int duration) {
        Datagram datagram = createFinishStateSearchDatagram(profilerId);
        return new ProfilerDatagramHolder(agentId, profilerId, datagram, duration);
    }

    private ProfilerDatagramHolder createStartStateSearchHolder(String agentId, String profilerId, int duration) {
        Datagram searchDatagram = createStartStateSearchDatagram(profilerId);
        return new ProfilerDatagramHolder(agentId, profilerId, searchDatagram, duration);
    }
}

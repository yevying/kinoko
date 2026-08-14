package kinoko.provider.reactor;

import kinoko.provider.ProviderError;
import kinoko.provider.WzProvider;
import kinoko.provider.wz.serialize.WzProperty;

import java.util.*;

public final class ReactorTemplate {
    private final int id;
    private final boolean notHitable;
    private final boolean activateByTouch;
    private final String action;
    private final Map<Integer, ReactorState> states;

    public ReactorTemplate(int id, boolean notHitable, boolean activateByTouch, String action, Map<Integer, ReactorState> states) {
        this.id = id;
        this.notHitable = notHitable;
        this.activateByTouch = activateByTouch;
        this.action = action;
        this.states = states;
    }

    public int getId() {
        return id;
    }

    public boolean isNotHitable() {
        return notHitable;
    }

    public boolean isActivateByTouch() {
        return activateByTouch;
    }

    public String getAction() {
        return action;
    }

    public Map<Integer, ReactorState> getStates() {
        return states;
    }

    public int getLastState() {
        // The final state is the largest state reachable through the event chains,
        // NOT merely the largest state key. e.g. 0002001 (Henesys box) has states 0-3
        // but its state-3 HIT event has nextState=4, so lastState must be 4; otherwise
        // isLastState() is never true and the reactor action/script never triggers.
        int lastState = 0;
        for (var entry : states.entrySet()) {
            lastState = Math.max(lastState, entry.getKey());
            for (ReactorEvent event : entry.getValue().getEvents()) {
                lastState = Math.max(lastState, event.getNextState());
            }
        }
        return lastState;
    }

    public Optional<ReactorEvent> getHitEvent(int state, int skillId) {
        final ReactorState reactorState = states.get(state);
        if (reactorState == null) {
            return Optional.empty();
        }
        for (ReactorEvent event : reactorState.getEvents()) {
            if ((event.getType() == ReactorEventType.HIT && skillId == 0) ||
                    (event.getType() == ReactorEventType.SKILL && event.getSkills().contains(skillId))) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    public Optional<ReactorEvent> getDropEvent(int state, int itemId, int x, int y) {
        final ReactorState reactorState = states.get(state);
        if (reactorState == null) {
            return Optional.empty();
        }
        for (ReactorEvent event : reactorState.getEvents()) {
            if (event.getType() == ReactorEventType.DROP &&
                    event.getItemId() == itemId) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    public static ReactorTemplate from(int reactorId, boolean notHitable, boolean activateByTouch, String action, WzProperty reactorProp) throws ProviderError {
        // Process states
        final Map<Integer, ReactorState> states = new HashMap<>();
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!(reactorProp.get(String.valueOf(i)) instanceof WzProperty stateProp)) {
                break;
            }
            int timeOut = 0;
            // Process events
            final List<ReactorEvent> events = new ArrayList<>();
            if (stateProp.get("event") instanceof WzProperty eventList) {
                timeOut = WzProvider.getInteger(eventList.get("timeOut"), 0);
                for (int j = 0; j < Integer.MAX_VALUE; j++) {
                    if (!(eventList.get(String.valueOf(j)) instanceof WzProperty eventProp)) {
                        break;
                    }
                    events.add(ReactorEvent.from(eventProp));
                }
            }
            states.put(i, new ReactorState(
                    Collections.unmodifiableList(events),
                    timeOut
            ));
        }
        return new ReactorTemplate(
                reactorId,
                notHitable,
                activateByTouch,
                action,
                Collections.unmodifiableMap(states)
        );
    }
}

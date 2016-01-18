package org.rapla.storage.impl.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.rapla.entities.Category;
import org.rapla.entities.Entity;
import org.rapla.entities.User;
import org.rapla.entities.configuration.internal.RaplaMapImpl;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.internal.AllocatableImpl;
import org.rapla.entities.domain.internal.ReservationImpl;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.internal.DynamicTypeImpl;
import org.rapla.entities.internal.CategoryImpl;
import org.rapla.entities.internal.UserImpl;
import org.rapla.facade.Conflict;
import org.rapla.facade.internal.ConflictImpl;
import org.rapla.framework.RaplaException;
import org.rapla.jsonrpc.common.internal.JSONParserWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EntityHistory
{
    public Collection<String> getAllIds()
    {
        return map.keySet();
    }

    public static boolean isSupportedEntity(Class<? extends Entity> type)
    {
        return type == Allocatable.class || type == DynamicType.class || type == Reservation.class || type == User.class || type == Category.class
                || type == Conflict.class;
    }

    public static class HistoryEntry
    {
        private long timestamp;
        private String id;
        private String json;
        Class typeClass;
        private boolean isDelete;

        private HistoryEntry()
        {
        }

        private HistoryEntry(String id, long timestamp, String json, Class typeClass, boolean isDelete)
        {
            super();
            this.id = id;
            this.isDelete = isDelete;
            this.timestamp = timestamp;
            this.json = json;
            this.typeClass = typeClass;
        }

        public Class getTypeClass()
        {
            return typeClass;
        }

        public String getId()
        {
            return id;
        }

        public long getTimestamp()
        {
            return timestamp;
        }

        public boolean isDelete()
        {
            return isDelete;
        }

        @Override public String toString()
        {
            return "HistoryEntry [timestamp=" + timestamp + ", id=" + id + "]";
        }
    }

    private final Map<String, List<EntityHistory.HistoryEntry>> map = new LinkedHashMap<String, List<EntityHistory.HistoryEntry>>();
    private final Gson gson;

    public EntityHistory()
    {
        Class[] additionalClasses = new Class[] { RaplaMapImpl.class };
        final GsonBuilder gsonBuilder = JSONParserWrapper.defaultGsonBuilder(additionalClasses);
        gson = gsonBuilder.create();
    }

    public HistoryEntry getLatest(String id)
    {
        final List<HistoryEntry> historyEntries = map.get(id);
        if (historyEntries == null || historyEntries.isEmpty())
        {
            // FIXME handle history ends
            throw new RaplaException("History not available for id " + id);
        }
        return historyEntries.get(historyEntries.size() - 1);
    }

    /** returns the history entry with a timestamp<= since or null if no such entry exists*/
    public Entity get(String id, Date since)
    {
        final List<EntityHistory.HistoryEntry> historyEntries = map.get(id);
        if (historyEntries == null)
        {
            // FIXME handle history ends
            throw new RaplaException("History not available for id " + id);
        }
        final EntityHistory.HistoryEntry emptyEntryWithTimestamp = new EntityHistory.HistoryEntry();
        emptyEntryWithTimestamp.timestamp = since.getTime();
        int index = Collections.binarySearch(historyEntries, emptyEntryWithTimestamp, new Comparator<EntityHistory.HistoryEntry>()
        {
            @Override public int compare(EntityHistory.HistoryEntry o1, EntityHistory.HistoryEntry o2)
            {
                return (int) (o1.timestamp - o2.timestamp);
            }
        });
        /*
        * possible results:
        * we get an index >= 0 -> We found an entry, which has the timestamp of the last update from the client. We need to get this one
        * we get an index < 0 -> We have no entry within the list, which has the timestamp. Corresponding to the binary search API -index -1 is the index where to insert a entry having this timestamp. So we need -index -1 to get the last one with an timestamp smaller than the requested one.
        */
        if (index < 0)
        {
            index = -index - 2;
        }
        if (index < 0)
        {
            return null;
        }
        EntityHistory.HistoryEntry entry = historyEntries.get(index);
        return getEntity(entry);
    }

    Map<Class<? extends Entity>, Class<? extends Entity>> typeImpl = new HashMap<Class<? extends Entity>, Class<? extends Entity>>();

    {
        addMap(Reservation.class, ReservationImpl.class);
        //addMap(Appointment.class, AppointmentImpl.class);
        addMap(Allocatable.class, AllocatableImpl.class);
        addMap(Category.class, CategoryImpl.class);
        addMap(User.class, UserImpl.class);
        addMap(Conflict.class, ConflictImpl.class);
        addMap(DynamicType.class, DynamicTypeImpl.class);

    }

    <T extends Entity> void addMap(Class<T> type, Class<? extends T> impl)
    {
        typeImpl.put(type, impl);
    }

    public Entity getEntity(HistoryEntry entry)
    {
        String json = entry.json;
        final Class typeClass = entry.typeClass;
        final Class<? extends Entity> implementingClass = typeImpl.get(typeClass);
        final Entity entity = gson.fromJson(json, implementingClass);
        return entity;
    }

    public EntityHistory.HistoryEntry addHistoryEntry(String id, String json, Class typeClass, Date timestamp, boolean isDelete)
    {
        List<EntityHistory.HistoryEntry> historyEntries = map.get(id);
        if (historyEntries == null)
        {
            historyEntries = new ArrayList<EntityHistory.HistoryEntry>();
            map.put(id, historyEntries);
        }
        final EntityHistory.HistoryEntry newEntry = new EntityHistory.HistoryEntry(id, timestamp.getTime(), json, typeClass, isDelete);
        int index = historyEntries.size();
        insert(historyEntries, newEntry, index);
        return newEntry;
    }

    private void insert(List<EntityHistory.HistoryEntry> historyEntries, EntityHistory.HistoryEntry newEntry, int index)
    {
        if (index == 0)
        {
            historyEntries.add(0, newEntry);
        }
        else
        {
            final long timestamp = historyEntries.get(index - 1).timestamp;
            if (timestamp > newEntry.timestamp)
            {
                insert(historyEntries, newEntry, index - 1);
            }
            else if (timestamp == newEntry.timestamp)
            {
                // Do nothing as already inserted... maybe check it
            }
            else
            {
                historyEntries.add(index, newEntry);
            }
        }
    }

    public EntityHistory.HistoryEntry addHistoryEntry(Entity entity, Date timestamp, boolean isDelete)
    {
        final String id = entity.getId();
        final String json = gson.toJson(entity);
        return addHistoryEntry(id, json, entity.getTypeClass(), timestamp, isDelete);
    }

    public EntityHistory.HistoryEntry addHistoryDeleteEntry(Entity entity, Date timestamp, boolean isDelete)
    {
        final String id = entity.getId();
        final String json = gson.toJson(entity);
        return addHistoryEntry(id, json, entity.getTypeClass(), timestamp, isDelete);
    }

    public void clear()
    {
        map.clear();
    }

    List<HistoryEntry> getHistoryList(String key)
    {
        return map.get(key);
    }

    public void removeUnneeded(Date date)
    {
        final Set<String> keySet = map.keySet();
        final long time = date.getTime();
        for (String key : keySet)
        {
            final List<HistoryEntry> list = map.get(key);
            while (list.size() >= 2 && list.get(1).timestamp < time)
            {
                list.remove(0);
            }
        }
    }

    /**
     * Returns the entity with the given id where timestamp >= last_changed from the entity.
     * @param id
     * @param timestamp
     * @return
     */
    public HistoryEntry getLastChangedUntil(String id, Date timestamp)
    {
        final long time = timestamp.getTime();
        final List<HistoryEntry> list = map.get(id);
        for (int i = list.size() - 1; i >= 0; i--)
        {
            final HistoryEntry historyEntry = list.get(i);
            if (historyEntry.getTimestamp() <= time)
            {
                return historyEntry;
            }
        }
        return null;
    }
}
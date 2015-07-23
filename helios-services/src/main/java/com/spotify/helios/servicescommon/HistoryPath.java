package com.spotify.helios.servicescommon;

import com.spotify.helios.common.QueueableEvent;

/**
 *
 */
public interface HistoryPath {

  /**
   * Get the path at which events should be stored. Generally the path will differ based on
   * some parameters of the event. For example, all events associated with a particular host
   * might be stored at a single path.
   *
   * All events will be stored as children of the returned path.
   *
   * @param event {@link QueueableEvent}
   * @return A ZooKeeper path.
   */
  String getZkEventsPath(QueueableEvent event);

}

import { Ctrl, NotifyOpts, NotifyData, UpdateBell, Redraw } from './interfaces';

import * as xhr from 'common/xhr';

export default function makeCtrl(opts: NotifyOpts, redraw: Redraw): Ctrl {
  let data: NotifyData | undefined,
    initiating = true,
    scrolling = false;

  const readAllStorage = lichess.storage.make('notify-read-all');

  readAllStorage.listen(_ => {
    if (data) {
      data.unread = 0;
      opts.setCount(0);
      redraw();
    }
  });

  function updateBell(d: UpdateBell) {
    if (opts.isVisible()) {
      loadPage(1);
      return;
    }
    data = undefined;
    opts.setCount(d.unread);
    opts.pulse();
    redraw();
  }

  function updateNotes(d: NotifyData) {
    data = d;
    if (data.pager.currentPage === 1 && data.unread && opts.isVisible()) {
      opts.setNotified();
      data.unread = 0;
      readAllStorage.fire();
    }
    initiating = false;
    scrolling = false;
    opts.setCount(data.unread);
    redraw();
  }

  const loadPage = (page: number) =>
    xhr.json(xhr.url('/notify', { page: page || 1 })).then(
      d => updateNotes(d),
      _ => lichess.announce({ msg: 'Failed to load notifications' })
    );

  function nextPage() {
    if (!data || !data.pager.nextPage) return;
    scrolling = true;
    loadPage(data.pager.nextPage);
    redraw();
  }

  function previousPage() {
    if (!data || !data.pager.previousPage) return;
    scrolling = true;
    loadPage(data.pager.previousPage);
    redraw();
  }

  function setVisible() {
    if (!data || data.pager.currentPage === 1) loadPage(1);
  }

  function setMsgRead(user: string) {
    if (data)
      data.pager.currentPageResults.forEach(n => {
        if (n.type == 'privateMessage' && n.content.user?.id == user && !n.read) {
          n.read = true;
          data!.unread = Math.max(0, data!.unread - 1);
          opts.setCount(data!.unread);
        }
      });
  }

  const emptyNotifyData = {
    pager: {
      currentPage: 1,
      maxPerPage: 1,
      currentPageResults: [],
      nbResults: 0,
      nbPages: 1,
    },
    unread: 0,
    i18n: {},
  };

  function clear() {
    xhr
      .text('/notify/clear', {
        method: 'post',
      })
      .then(
        _ => updateNotes(emptyNotifyData),
        _ => lichess.announce({ msg: 'Failed to clear notifications' })
      );
  }

  return {
    data: () => data,
    initiating: () => initiating,
    scrolling: () => scrolling,
    updateBell,
    updateNotes,
    nextPage,
    previousPage,
    loadPage,
    setVisible,
    setMsgRead,
    clear,
  };
}

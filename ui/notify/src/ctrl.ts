import { Ctrl, NotifyOpts, NotifyData, Redraw } from './interfaces';

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

  function bumpUnread() {
    if (opts.isVisible()) {
      loadPage(1);
      return;
    }
    data = undefined;
    opts.setCount('increment');
    attention();
    redraw();
  }

  function updateNotes(d: NotifyData) {
    data = d;
    /*if (data.pager.currentPage === 1 && data.unread && opts.isVisible()) {
      opts.setNotified();
      data.unread = 0;
      readAllStorage.fire();
    }*/
    initiating = false;
    scrolling = false;
    if (opts.setCount(data.unread) && data.unread) attention(data);
    redraw();
  }
  
  function attention(d?: NotifyData) {
    const id = d?.pager.currentPageResults.find(n => !n.read)?.content.user?.id
    if (!lichess.quietMode || id == 'lichess') lichess.sound.playOnce('newPM')
    opts.pulse();
  }

  const loadPage = (page: number) => {
    console.log(`fetching page ${page}`)
    xhr.json(xhr.url('/notify', { page: page || 1 })).then(
      d => updateNotes(d),
      _ => lichess.announce({ msg: 'Failed to load notifications' })
    );
    }
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
    if (!data) loadPage(1);
    else if (data.pager.currentPage == 1) {
      console.log('doin the gogy')
      opts.setNotified();
      data.unread = 0;
      readAllStorage.fire();
    }
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
    bumpUnread,
    updateNotes,
    nextPage,
    previousPage,
    loadPage,
    setVisible,
    setMsgRead,
    clear,
  };
}

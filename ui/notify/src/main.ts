import { init, classModule, attributesModule } from 'snabbdom';
import makeCtrl from './ctrl';
import view from './view';
import { NotifyOpts, NotifyData, UpdateBell } from './interfaces';

const patch = init([classModule, attributesModule]);

export default function LichessNotify(element: Element, opts: NotifyOpts) {
  const ctrl = makeCtrl(opts, redraw);
  let vnode = patch(element, view(ctrl));

  function redraw() {
    vnode = patch(vnode, view(ctrl));
  }
  const update = (data: NotifyData|UpdateBell) => ('pager' in data) ? ctrl.updateNotes(data) : ctrl.updateBell(data);
  
  if (opts.data) update(opts.data)
  else ctrl.loadPage(1);

  return {
    update: update,
    setVisible: ctrl.setVisible,
    setMsgRead: ctrl.setMsgRead,
    redraw,
  };
}

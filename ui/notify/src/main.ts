import { init, classModule, attributesModule } from 'snabbdom';
import makeCtrl from './ctrl';
import view from './view';
import { NotifyOpts } from './interfaces';

const patch = init([classModule, attributesModule]);

export default function LichessNotify(element: Element, opts: NotifyOpts) {
  const ctrl = makeCtrl(opts, redraw);
  let vnode = patch(element, view(ctrl));

  function redraw() {
    vnode = patch(vnode, view(ctrl));
  }

  if (opts.data)
    ('pager' in opts.data) ? ctrl.updateNotes(opts.data) : ctrl.updateBell(opts.data);
  else 
    ctrl.loadPage(1);

  return {
    updateBell: ctrl.updateBell,
    updateNotes: ctrl.updateNotes,
    setVisible: ctrl.setVisible,
    setMsgRead: ctrl.setMsgRead,
    redraw,
  };
}

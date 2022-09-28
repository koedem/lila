import * as xhr from 'common/xhr';
//import throttle from 'common/throttle';
lichess.load.then(() => $('.ask-container').each((_, e: EleLoose) => new Ask(e.firstElementChild!)));

// i have yet to be accused me of proper OOP

class Ask {
  el: Element;
  submitEl?: Element;
  feedbackEl?: HTMLInputElement;

  constructor(askEl: Element) {
    this.el = askEl;
    wireExclusiveChoices(this);
    wireRankedChoices(this);
    this.feedbackEl = wireFeedback(this);
    this.submitEl = wireSubmit(this);
  }
  ranking = (): string => 
    Array.from($('.ranked-choice', this.el), e => e?.getAttribute('value')).join('-');

  setState = (state: 'clean' | 'dirty' | 'success') => {
    this.submitEl?.classList.remove('dirty', 'success');
    if (state != 'clean') this.submitEl?.classList.add(state);
  };
}

const rewire = (el: Element | null, frag: string): Ask | undefined => {
  while (el && !el.classList.contains('ask-container')) el = el.parentElement;
  if (el && frag) {
    el.innerHTML = frag;
    return new Ask(el.firstElementChild!);
  }
};

const failure = (reason: any): void => {
  console.log(`Ask XHR failed with: ${reason}`);
};

const wireExclusiveChoices = (ask: Ask): void => {
  $('.exclusive-choice', ask.el).on('click', function (e: Event) {
    const target = e.target as Element;
    const value = target.classList.contains('selected') ? '' : target?.getAttribute('value');
    xhr
      .text(`/ask/${ask.el.id}?picks=${value}`, { method: 'post' })
      .then((frag: string) => rewire(ask.el, frag));
  });
};

const wireRankedChoices = (ask: Ask): void => {
  
  let ctx: DragContext;
  let initialOrder = ask.ranking();

  $('.ranked-choice', ask.el) // for each draggable
    .on('dragstart', (e: DragEvent) => {
      ctx = new DragContext( e, true, false);
    })
    .on('dragend', (e: DragEvent) => {
      if (!ctx.drop(e)) return;
      const newOrder = ask.ranking();
      if (newOrder == initialOrder) return;
      const path = `/ask/${ask.el.id}?picks=${newOrder}`;
      xhr.text(path, { method: 'post' }).then(() => { initialOrder = newOrder; });
    });

  $('.ask__choices', ask.el) // and for the container
    .on('dragover', (e: MouseEvent) => ctx.updateCursor(e))
    .on('dragleave', (e: MouseEvent) => ctx.updateCursor(e));
};

const wireFeedback = (ask: Ask): HTMLInputElement|undefined => {
  const feedbackEl = $('.feedback', ask.el)
    .on('input', () => 
      ask.setState(ask.feedbackEl?.value == initialFeedback ? 'clean' : 'dirty')
    )
    .on('keypress', (e: KeyboardEvent) => {
      if (
        e.key != 'Enter' ||
        e.shiftKey ||
        e.ctrlKey ||
        e.altKey ||
        e.metaKey ||
        !ask.submitEl?.classList.contains('dirty')
      )
        return;
      $('input', ask.submitEl).trigger('click');
      e.preventDefault();
    })
    .get(0) as HTMLInputElement;
  const initialFeedback = feedbackEl?.value;
  return feedbackEl;
};

const wireSubmit = (ask: Ask): Element|undefined => {
  const submitEl = $('.ask__submit', ask.el).get(0);
  $('input', submitEl).on('click', () => {
    const path = `/ask/feedback/${ask.el.id}`;
    xhr
      .text(path, {
        method: 'post', 
        body: ask.feedbackEl?.value && xhr.form({ text: ask.feedbackEl?.value })
      })
      .then(
        (frag: string) =>
          rewire(ask.el, frag)?.setState(ask.feedbackEl?.value ? 'success' : 'clean'),
        failure
      );
  });
  return submitEl
};
  class DragContext {
    dragging: Element;
    parent: Element;
    box: DOMRect;
    originalNext: Element|null;
    cursorDiv: Element;
    breakDiv: HTMLElement;
    vertical: boolean;
    stretch: boolean;
    rtl: boolean;
    choices: Array<Element>;
    mouse: {x:number, y:number};

    constructor(e: DragEvent, vertical: boolean, stretch: boolean) {
      this.dragging = e.target as Element;
      e.dataTransfer!.effectAllowed = 'move';
      e.dataTransfer!.setData('text/plain', '');
      this.dragging.classList.add('dragging');
      this.parent = this.dragging.parentElement!;
      this.box = this.parent.getBoundingClientRect();
      this.cursorDiv = document.createElement('div');
      this.cursorDiv.classList.add('cursor');
      this.breakDiv = document.createElement('div');
      this.breakDiv.style.flexBasis = '100%';
      const label = document.createElement('label');
      label.innerText = '\ue072';
      this.cursorDiv.appendChild(label);

      this.vertical = vertical;
      this.stretch = stretch;
      this.choices = Array.from($('.ranked-choice', this.parent), e => e!);
      this.mouse = {x:0, y:0};
    }

    drop(e: DragEvent): boolean {
      e.preventDefault();
      this.dragging.classList.remove('dragging');
      if (this.cursorDiv.parentElement == this.parent) {
        this.parent.insertBefore(this.dragging, this.cursorDiv);
      }
      //else this.parent.insertBefore
        this.clearCursor();
      return true;
    }
    // need a separate version of this function for rtl layouts
    updateCursor(e: MouseEvent) {
      e.preventDefault();
      if (this.mouse.x == e.x && this.mouse.y == e.y) return;
      this.mouse = {x:e.x, y:e.y};
      const box = this.box;
      if (e.x <= box.left || e.x >= box.right || e.y <= box.top || e.y >= box.bottom) {
        this.clearCursor();
        return;
      }
      type Target = { el: Element|null, break: undefined|'beforebegin'|'afterend' }
      let target: Target|null = null;
      for (let i = 0, lastY = 0; !target && i < this.choices.length; i++ ) {
        const r = this.choices[i].getBoundingClientRect();
        const el = this.choices[i];
        const x = r.right - r.width / 2;
        const y = r.bottom + 4;
        if (i > 0 && y != lastY && e.x <= box.right && e.y <= lastY)
          target = { el: el, break: 'afterend' };
        else if (e.x <= x && e.y <= y)
          target = { el: el, break: (i > 0 && y != lastY) ? 'beforebegin' : undefined };
        lastY = y;
      }
      if (target) {
        this.parent.insertBefore(this.cursorDiv, target.el);
        if (target.break)
          this.cursorDiv.insertAdjacentElement(target.break, this.breakDiv);
        else if (this.breakDiv.parentNode)
          this.parent.removeChild(this.breakDiv);
      }
      else this.parent.insertBefore(this.cursorDiv, null);
    }
    clearCursor(): void {
      if (this.cursorDiv.parentNode) this.parent.removeChild(this.cursorDiv);
      if (this.breakDiv.parentNode) this.parent.removeChild(this.breakDiv);
    }
  }

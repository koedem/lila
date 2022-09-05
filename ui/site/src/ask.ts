import * as xhr from 'common/xhr';

lichess.load.then(() => {
  $('div.ask-container').each(function (this: HTMLElement) {
    rewire(this);
    const ask = this.firstElementChild as HTMLElement;
    $('.ask-ranked',ask).each(bindRankedAsk)
  });
});

function rewire(container: HTMLElement){
  const ask = container.firstElementChild as HTMLElement;
  $('.ask-xhr', ask).on('click', function (e: Event) {
    xhr.text((e.target as HTMLButtonElement).formAction, { method: 'post' }).then((frag: string) => {
      container!.innerHTML = frag;
      rewire(container);
    });
  });
  return false;
}

function getElementTextArray(ask: Element): Array<string|null> {
  return Array.from($('.ask-ranked-choice', ask), (e: EleLoose) => e?.textContent);
}

function bindRankedAsk (_: any, ask: Element): void {
  let dragging: Element|null = null;
  let origAfter: Element|null = null;
  let serverOrder = getElementTextArray(ask);
  $('.ask-ranked-choice', ask).on('dragover',  (e: DragEvent) => {
    if (!dragging) return;
    const target = e.target as Element;
    if (target) e.preventDefault();
    target.parentNode?.insertBefore(dragging, isBefore(dragging, target) ? target : target?.nextSibling);
  }).on('dragstart', (e: DragEvent) => {
    dragging = e.target as Element;
    origAfter = dragging.nextElementSibling;
    e.dataTransfer!.effectAllowed = "move";
    e.dataTransfer!.setData("text/plain", '');
  }).on('drop', (e: DragEvent) => {
    const currentOrder = getElementTextArray(ask);
    console.log(currentOrder);
    if (currentOrder.toString() != serverOrder.toString()) {
      console.log('xhr here');
      e.preventDefault();
      serverOrder = currentOrder;
    }
    dragging = null;
    origAfter = null;
  }).on('dragend', () => {
    // drop was unsuccessful. reset to serverOrder
    if (!dragging) return;
    dragging.parentNode?.insertBefore(dragging, origAfter);
    dragging = null;
    origAfter = null;
  });
}
function isBefore(dragging: Element, target: Element|null) {
  if (target?.parentNode === dragging.parentNode)
    for (var cur = dragging.previousSibling; cur && cur.nodeType !== 9; cur = cur.previousSibling)
      if (cur === target)
        return true;
  return false;
}
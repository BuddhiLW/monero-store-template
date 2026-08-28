# The architecture is data

`models/monero-store/` holds the system as an [Overarch][overarch] model. The
diagrams under `docs/diagrams/` are generated from it and are build artifacts —
editing one is editing the wrong file.

```
bb arch     # models/*.edn -> docs/diagrams/plantuml/monero-store/*.puml
```

or directly:

```
clojure -M:arch --model-dir models --render-format plantuml --render-dir docs/diagrams
```

## What is in the model

| File | Holds |
|---|---|
| `model.edn` | the elements and the relations between them — people, the store and its containers, the components of the payment seam, and every external system |
| `views.edn` | the C4 views: which elements each diagram shows, and in which direction the arrows run |
| `state.edn` | the invoice lifecycle as a state machine, and its view |

Model and views are separate on purpose. An element is declared once and appears
in as many views as are useful, each view choosing its own subset and layout —
so a container can be a black box in one diagram and opened up in the next
without being described twice.

## The views

| View | Answers |
|---|---|
| `context-view` | who uses the store, and every system it needs to take money |
| `container-view` | the CPPB strata as parts, and that dependencies point one way |
| `rails-view` | the DIP seam: a rail is a registry entry, and `settle` reads a profile |
| `ports-view` | every protocol a deployment satisfies, and what satisfies it today |
| `integration-view` | one picture of every external system and which way each integration runs |
| `invoice-lifecycle-view` | what moves an invoice, including the transitions that move it nowhere |

## Rendering the PlantUML

The `.puml` files are text and readable as they are. To turn them into images
you need PlantUML itself and the C4 standard library, which the generated files
`!include`. Overarch can also emit GraphViz and Markdown:

```
clojure -M:arch --model-dir models --render-format markdown --render-dir docs/diagrams
```

## Keeping it honest

The model is hand-authored, not derived from the namespaces — a diagram
generated from a call graph shows every namespace as a box and answers no
question anyone actually has. The cost of hand-authoring is that the model can
drift from the code, and nothing here prevents that.

What does help: Overarch fails loudly on a reference to an element that does not
exist, so a renamed or deleted element cannot pass silently. `bb arch` reports

```
{:build-problems (), :unresolved-refs-in-views (), :unresolved-refs-in-relations ()}
```

and anything non-empty there is a model that no longer describes itself.

[overarch]: https://github.com/soulspace-org/overarch
